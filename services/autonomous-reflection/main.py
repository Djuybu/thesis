from __future__ import annotations

import logging
import os
import joblib
import pandas as pd
import json
import time
import numpy as np
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel
from typing import List, Optional
from sentence_transformers import SentenceTransformer, util

from ingest_models import IngestRequest, IngestResponse
import ingest_service

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
logging.getLogger("ingest_service").setLevel(logging.INFO)

_DEBUG_LOG_PATH = "/home/djuybu/thesis/.cursor/debug-205876.log"
_DEBUG_SESSION = "205876"


def _agent_debug_log(
    hypothesis_id: str,
    location: str,
    message: str,
    data: dict,
    run_id: str = "pre-fix",
) -> None:
    # #region agent log
    try:
        payload = {
            "sessionId": _DEBUG_SESSION,
            "runId": run_id,
            "hypothesisId": hypothesis_id,
            "location": location,
            "message": message,
            "data": data,
            "timestamp": int(time.time() * 1000),
        }
        with open(_DEBUG_LOG_PATH, "a", encoding="utf-8") as _df:
            _df.write(json.dumps(payload, default=str) + "\n")
    except Exception:
        pass
    # #endregion


# Dremio AccelCreateReflectionHandler: SUM only on numeric (SqlTypeFamily.NUMERIC / ANY);
# CHARACTER / TIMESTAMP / etc. allow APPROX_COUNT_DISTINCT, COUNT, MIN, MAX (default COUNT).
_MEASURE_TYPES_NUMERIC_OK = frozenset(
    {
        "DOUBLE",
        "FLOAT",
        "REAL",
        "INTEGER",
        "INT",
        "BIGINT",
        "SMALLINT",
        "TINYINT",
        "DECIMAL",
        "NUMERIC",
        "NUMBER",
    }
)
_MEASURE_AGG_CONSERVATIVE = frozenset(
    {"APPROX_COUNT_DISTINCT", "COUNT", "MIN", "MAX"}
)
_MEASURE_AGG_WITH_SUM = _MEASURE_AGG_CONSERVATIVE | frozenset({"SUM"})


def _normalize_sql_type(type_str: Optional[str]) -> str:
    return (type_str or "").strip().upper()


def _column_type_index(columns: list) -> dict:
    return {c.name: _normalize_sql_type(c.type) for c in columns}


def _adjust_measure_aggregations(sql_type: str, agg_list: List[str]) -> List[str]:
    """
    Filter measure aggregations to types Dremio accepts on CREATE REFLECTION.
    AVG is not a reflection MeasureType — strip it. Non-numeric columns cannot use SUM.
    """
    t = _normalize_sql_type(sql_type)
    raw = [str(a).strip().upper() for a in agg_list if a is not None and str(a).strip()]
    raw = [a for a in raw if a != "AVG"]

    is_numeric = t in _MEASURE_TYPES_NUMERIC_OK
    allowed = _MEASURE_AGG_WITH_SUM if is_numeric else _MEASURE_AGG_CONSERVATIVE
    filtered = [a for a in raw if a in allowed]
    if filtered:
        return filtered
    return ["SUM", "COUNT"] if is_numeric else ["COUNT"]


def _encoder_default_model_name() -> str:
    return os.getenv("REFLECTION_ENCODER_MODEL", "all-MiniLM-L6-v2")


def _repair_brain_encoder_if_needed(brain) -> None:
    """
    joblib-unpickled SentenceTransformer can be incompatible with the installed
    sentence-transformers (e.g. missing `.device`), breaking `encode()`.
    Reinstantiate the encoder and rebuild embeddings from `knowledge_base` keys.
    """
    if brain is None or not getattr(brain, "encoder", None):
        return
    enc = brain.encoder
    broken = False
    try:
        _ = enc.device
    except Exception:
        broken = True
    if not broken:
        try:
            enc.encode(["__ar_encoder_probe__"], show_progress_bar=False)
        except Exception:
            broken = True
    if not broken:
        return

    model_name = _encoder_default_model_name()
    old = enc
    for candidate in (
        getattr(old, "model_name", None),
        getattr(getattr(old, "model", None), "config", None)
        and getattr(old.model.config, "_name_or_path", None),
    ):
        if isinstance(candidate, str) and candidate.strip():
            model_name = candidate.strip()
            break

    print(
        f"Repairing pickled SentenceTransformer: reinstantiating encoder ({model_name!r}) "
        "and rebuilding embeddings."
    )
    brain.encoder = SentenceTransformer(model_name)
    kb = getattr(brain, "knowledge_base", None)
    if kb and hasattr(brain, "_build_embeddings"):
        brain._build_embeddings()


# ---- CUSTOM MODEL CLASS ----
class ReflectionBrain:
    def __init__(self, model_name='all-MiniLM-L6-v2'):
        self.encoder = SentenceTransformer(model_name)
        self.knowledge_base = {} 
        self.threshold = 0.7 

    def _extract_list(self, value):
        if isinstance(value, str):
            return [x.strip() for x in value.replace('[','').replace(']','').replace("'", "").split(',') if x]
        return value if isinstance(value, list) else []

    def train_from_labeled_data(self, labeled_list):
        for item in labeled_list:
            try:
                data = json.loads(item)
                col_name = data.get('column')
                label = data.get('type', '').lower()
                if not col_name or not label:
                    continue
                label_type = 'dim' if 'dimension' in label else 'mea'
                self._update_knowledge(col_name, label_type)
            except json.JSONDecodeError:
                continue
        self._build_embeddings()

    def train(self, df):
        # Implementation hidden for brevity relative to Dremio inference
        pass

    def _update_knowledge(self, col_name, label_type):
        if col_name not in self.knowledge_base:
            self.knowledge_base[col_name] = {'dim_score': 0, 'mea_score': 0}
        
        if label_type == 'dim':
            self.knowledge_base[col_name]['dim_score'] += 1
        else:
            self.knowledge_base[col_name]['mea_score'] += 1

    def _build_embeddings(self):
        names = list(self.knowledge_base.keys())
        embeddings = self.encoder.encode(names)
        for i, name in enumerate(names):
            self.knowledge_base[name]['embedding'] = embeddings[i]

    def predict_reflection(self, new_table_columns):
        print(f"predict_reflection called with columns: {new_table_columns}")
        suggestions = []
        known_names = list(self.knowledge_base.keys())

        if len(known_names) == 0:
            # Fallback if knowledge base is empty
            for col in new_table_columns:
                suggestions.append({'column': col, 'suggested_type': 'None', 'similarity': 0.0, 'matched_with': 'None'})
            return pd.DataFrame(suggestions)

        known_embeddings = np.array([v['embedding'] for v in self.knowledge_base.values()])
        new_embeddings = self.encoder.encode(new_table_columns)

        for i, col in enumerate(new_table_columns):
            cos_scores = util.cos_sim(new_embeddings[i], known_embeddings)[0]
            best_match_idx = int(np.argmax(cos_scores))
            max_score = float(cos_scores[best_match_idx])

            if max_score >= self.threshold:
                matched_name = known_names[best_match_idx]
                info = self.knowledge_base[matched_name]
                label = "Dimension" if info['dim_score'] >= info['mea_score'] else "Measure"
                confidence = max_score
            else:
                label = "None"
                confidence = 0.0

            suggestions.append({
                'column': col,
                'suggested_type': label,
                'similarity': confidence,
                'matched_with': matched_name if max_score >= self.threshold else "None"
            })
        
        # print(f"Suggestions: {suggestions}")
        return pd.DataFrame(suggestions)

# ---- MODEL LOADING ----
ml_models = {}

@asynccontextmanager
async def lifespan(app: FastAPI):
    model_path = os.getenv("MODEL_PATH", "reflection_brain.pkl")
    import sys
    import __main__
    
    # Overwrite the empty ReflectionBrain from __main__ with our true functional class
    setattr(sys.modules['__main__'], 'ReflectionBrain', ReflectionBrain)
    
    try:
        if os.path.exists(model_path):
            print(f"Loading model from {model_path}...")
            ml_models['brain'] = joblib.load(model_path)
            _repair_brain_encoder_if_needed(ml_models["brain"])
            print("Model loaded successfully.")
        else:
            print(f"WARNING: Model file not found at {model_path}. Starting in degraded mode.")
            ml_models['brain'] = None
    except Exception as e:
        print(f"ERROR: Failed to load model: {str(e)}")
        ml_models['brain'] = None
        
    yield
    ml_models.clear()

app = FastAPI(title="Autonomous Reflection AI Service", lifespan=lifespan)

# ---- SCHEMAS ----
class ColumnMeta(BaseModel):
    name: str # Tên cột (e.g., 'customer_id')
    type: str # Kiểu dữ liệu gốc (e.g., 'VARCHAR', 'DOUBLE')
    # Removed uniqueCount dependency

class PredictionRequest(BaseModel):
    datasetPath: List[str]
    columns: List[ColumnMeta]

class ColumnPredictionDetail(BaseModel):
    column: str
    suggested_type: str
    similarity: float
    matched_with: str

class MeasurePrediction(BaseModel):
    name: str
    aggregations: List[str]

class PredictionResponse(BaseModel):
    datasetPath: List[str]
    dimensions: List[str]
    measures: List[MeasurePrediction]
    details: List[ColumnPredictionDetail]  # Cung cấp chi tiết độ tin cậy để bề mặt UI của Dremio có thể show nếu cần

# ---- ENDPOINTS ----
INGEST_TOKEN = os.getenv("INGEST_SHARED_SECRET", "")

@app.get("/health")
async def health_check():
    return {
        "status": "up",
        "model_loaded": ml_models.get('brain') is not None,
        "last_ingest_batch_id": ml_models.get('_last_ingest_batch_id'),
    }

@app.post("/predict/schema", response_model=PredictionResponse)
async def predict_schema(req: PredictionRequest):
    model = ml_models.get('brain')
    col_names = [c.name for c in req.columns]
    type_by_col = _column_type_index(req.columns)
    # #region agent log
    _agent_debug_log(
        "H1",
        "main.py:predict_schema:entry",
        "predict_schema columns and types",
        {"datasetPath": req.datasetPath, "typeByCol": type_by_col, "brainLoaded": model is not None},
    )
    # #endregion

    if model is None:
        dimensions = [c.name for c in req.columns if c.type in ("VARCHAR", "BOOLEAN", "TIMESTAMP")]
        # Create default measure item for the fallback fields
        measures = [
            MeasurePrediction(
                name=c.name,
                aggregations=_adjust_measure_aggregations(
                    _normalize_sql_type(c.type), ["SUM", "AVG"]
                ),
            )
            for c in req.columns
            if c.type in ("DOUBLE", "FLOAT", "INTEGER", "DECIMAL")
        ]
        return PredictionResponse(
            datasetPath=req.datasetPath,
            dimensions=dimensions,
            measures=measures,
            details=[]
        )
    
    try:
        df_suggestions = model.predict_reflection(col_names)
        dimensions = []
        measures = []
        details = []
        
        for _, row in df_suggestions.iterrows():
            col_name = row['column']
            sug_type_raw = row.get('suggested_type')
            if sug_type_raw is None or pd.isna(sug_type_raw):
                sug_type_raw = "none"
            sug_type = str(sug_type_raw).lower()
            
            if "none" in sug_type:
                # Do not suggest it for dimension or measure
                pass
            elif 'dimension' in sug_type:
                dimensions.append(col_name)
            else:
                # Handle aggregations
                aggs_raw = row.get('aggregations')
                if isinstance(aggs_raw, str):
                    agg_list = [a.strip().upper() for a in aggs_raw.split(',') if a.strip()]
                elif isinstance(aggs_raw, list):
                    agg_list = [str(a).upper() for a in aggs_raw]
                else:
                    agg_list = ["SUM", "AVG"]  # Default if not provided (adjusted per SQL type below)

                sql_t = type_by_col.get(col_name, "")
                adjusted = _adjust_measure_aggregations(sql_t, agg_list)
                # #region agent log
                _agent_debug_log(
                    "H2",
                    "main.py:predict_schema:measure",
                    "measure aggregations before/after type guard",
                    {
                        "column": col_name,
                        "sqlType": sql_t,
                        "rawAgg": agg_list,
                        "adjustedAgg": adjusted,
                    },
                )
                # #endregion

                measures.append(MeasurePrediction(name=col_name, aggregations=adjusted))
                
            # Log all details regardless of type for UI diagnostics
            details.append(ColumnPredictionDetail(
                column=col_name,
                suggested_type=row.get('suggested_type', "none"),
                similarity=row.get('similarity', 0.0),
                matched_with=str(row.get('matched_with', 'None'))
            ))

        response_obj = PredictionResponse(
            datasetPath=req.datasetPath,
            dimensions=dimensions,
            measures=measures,
            details=details
        )
        print(f"Response: {response_obj}")
            
        return response_obj
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


def _verify_ingest_token(request: Request) -> None:
    if not INGEST_TOKEN:
        return
    provided = request.headers.get("X-AR-Ingest-Token", "")
    if provided != INGEST_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid or missing ingest token")


@app.post("/knowledge/ingest", response_model=IngestResponse)
async def post_knowledge_ingest(body: IngestRequest, request: Request):
    _verify_ingest_token(request)

    if ingest_service.is_duplicate(body.batchId):
        return IngestResponse(
            accepted=True,
            datasetsProcessed=0,
            columnsUpdated=0,
            skippedDuplicate=True,
        )

    brain = ml_models.get('brain')
    if brain is None:
        raise HTTPException(status_code=503, detail="Model not loaded; cannot ingest usage data")

    result = ingest_service.apply_ingest(brain, body)
    ml_models['_last_ingest_batch_id'] = body.batchId
    return result
