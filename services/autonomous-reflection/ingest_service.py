# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Stateless helpers that map an IngestRequest onto ReflectionBrain updates.

The module keeps a set of processed batch IDs (in-memory; lost on restart)
to provide idempotency within a single service lifetime.
"""

from __future__ import annotations

import logging
from typing import Dict, List, Set, Tuple

from ingest_models import IngestRequest, IngestResponse

logger = logging.getLogger(__name__)

_processed_batches: Set[str] = set()

DIM_USAGE_KEYS = ("projectionCount", "filterCount", "groupByCount")
MEA_USAGE_KEY = "aggregateCount"


def is_duplicate(batch_id: str) -> bool:
    return batch_id in _processed_batches


def _inferred_reflection_role(dim_score: int, mea_score: int) -> str:
    if dim_score <= 0 and mea_score <= 0:
        return "none"
    return "dimension" if dim_score >= mea_score else "measure"


def _column_scores(brain, column: str) -> Tuple[int, int]:
    entry = brain.knowledge_base.get(column)
    if not entry:
        return 0, 0
    return int(entry.get("dim_score", 0)), int(entry.get("mea_score", 0))


def _reflection_schema_for_columns(
    brain, column_names: List[str]
) -> Dict[str, List[str]]:
    dimensions: List[str] = []
    measures: List[str] = []
    for col in column_names:
        dim_score, mea_score = _column_scores(brain, col)
        role = _inferred_reflection_role(dim_score, mea_score)
        if role == "dimension":
            dimensions.append(col)
        elif role == "measure":
            measures.append(col)
    return {"dimensions": dimensions, "measures": measures}


def _log_reflection_schema_changes(
    brain,
    req: IngestRequest,
    touched_columns: List[str],
    scores_before: Dict[str, Tuple[int, int]],
) -> None:
    """Log how ingest shifts inferred raw/agg reflection roles (dimension vs measure)."""
    if not touched_columns:
        return

    role_changes: List[str] = []
    score_changes: List[str] = []
    for col in touched_columns:
        before_dim, before_mea = scores_before.get(col, (0, 0))
        before_role = _inferred_reflection_role(before_dim, before_mea)
        after_dim, after_mea = _column_scores(brain, col)
        after_role = _inferred_reflection_role(after_dim, after_mea)

        if before_role != after_role:
            role_changes.append(f"{col}: {before_role} -> {after_role}")
        if before_dim != after_dim or before_mea != after_mea:
            score_changes.append(
                f"{col}: dim {before_dim}->{after_dim}, mea {before_mea}->{after_mea}"
            )

    for ds in req.datasets:
        cols = [cu.column for cu in ds.columnUsage]
        schema = _reflection_schema_for_columns(brain, cols)
        path = ".".join(ds.datasetPath)
        logger.info(
            "Reflection schema after ingest batch %s for dataset %s: "
            "dimensions=%s measures=%s",
            req.batchId,
            path,
            schema["dimensions"],
            schema["measures"],
        )

    if role_changes:
        logger.info(
            "Reflection role changes after ingest batch %s: %s",
            req.batchId,
            "; ".join(role_changes),
        )
    if score_changes:
        logger.info(
            "Knowledge score deltas for batch %s: %s",
            req.batchId,
            "; ".join(score_changes),
        )


def apply_ingest(brain, req: IngestRequest) -> IngestResponse:
    """
    Map column-level usage counts onto ReflectionBrain knowledge.

    Heuristic:
      - projectionCount / filterCount / groupByCount  →  dimension signal
      - aggregateCount  →  measure signal

    Each signal increments the corresponding score by its count value,
    so columns queried 100 times weigh more than columns queried once.
    """
    if is_duplicate(req.batchId):
        return IngestResponse(
            accepted=True,
            datasetsProcessed=0,
            columnsUpdated=0,
            skippedDuplicate=True,
        )

    columns_updated = 0
    touched_columns: List[str] = []
    pending_updates: List[Tuple[str, int, int]] = []

    for ds in req.datasets:
        for cu in ds.columnUsage:
            dim_signal = cu.projectionCount + cu.filterCount + cu.groupByCount
            mea_signal = cu.aggregateCount

            if dim_signal == 0 and mea_signal == 0:
                continue

            pending_updates.append((cu.column, dim_signal, mea_signal))

    scores_before: Dict[str, Tuple[int, int]] = {}
    for column, dim_signal, mea_signal in pending_updates:
        scores_before[column] = _column_scores(brain, column)
        if column not in brain.knowledge_base:
            brain.knowledge_base[column] = {"dim_score": 0, "mea_score": 0}

        brain.knowledge_base[column]["dim_score"] += dim_signal
        brain.knowledge_base[column]["mea_score"] += mea_signal
        touched_columns.append(column)
        columns_updated += 1

    if columns_updated > 0:
        brain._build_embeddings()
        _log_reflection_schema_changes(brain, req, touched_columns, scores_before)

    _processed_batches.add(req.batchId)
    logger.info(
        "Ingested batch %s  window=[%d, %d]  datasets=%d  columns=%d",
        req.batchId,
        req.windowStartEpochMs,
        req.windowEndEpochMs,
        len(req.datasets),
        columns_updated,
    )

    return IngestResponse(
        accepted=True,
        datasetsProcessed=len(req.datasets),
        columnsUpdated=columns_updated,
        skippedDuplicate=False,
    )


def clear_processed_batches() -> None:
    """For testing only."""
    _processed_batches.clear()
