import os
import joblib
import pandas as pd
import json
import numpy as np
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from sentence_transformers import SentenceTransformer, util

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
                suggestions.append({'column': col, 'suggested_type': 'Dimension', 'similarity': 0.0, 'matched_with': 'None'})
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
                label = "Dimension"
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
@app.get("/health")
async def health_check():
    return {
        "status": "up",
        "model_loaded": ml_models.get('brain') is not None
    }

@app.post("/predict/schema", response_model=PredictionResponse)
async def predict_schema(req: PredictionRequest):
    model = ml_models.get('brain')
    col_names = [c.name for c in req.columns]
    
    if model is None:
        dimensions = [c.name for c in req.columns if c.type in ("VARCHAR", "BOOLEAN", "TIMESTAMP")]
        # Create default measure item for the fallback fields
        measures = [
            MeasurePrediction(name=c.name, aggregations=["SUM", "COUNT"]) 
            for c in req.columns if c.type in ("DOUBLE", "FLOAT", "INTEGER", "DECIMAL")
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
            sug_type = row.get('suggested_type', "none").lower()
            
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
                    agg_list = ["SUM", "COUNT"] # Default if not provided
                    
                measures.append(MeasurePrediction(name=col_name, aggregations=agg_list))
                
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
