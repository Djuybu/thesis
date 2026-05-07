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
from typing import Set

from ingest_models import IngestRequest, IngestResponse

logger = logging.getLogger(__name__)

_processed_batches: Set[str] = set()

DIM_USAGE_KEYS = ("projectionCount", "filterCount", "groupByCount")
MEA_USAGE_KEY = "aggregateCount"


def is_duplicate(batch_id: str) -> bool:
    return batch_id in _processed_batches


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

    for ds in req.datasets:
        for cu in ds.columnUsage:
            dim_signal = cu.projectionCount + cu.filterCount + cu.groupByCount
            mea_signal = cu.aggregateCount

            if dim_signal == 0 and mea_signal == 0:
                continue

            if cu.column not in brain.knowledge_base:
                brain.knowledge_base[cu.column] = {"dim_score": 0, "mea_score": 0}

            brain.knowledge_base[cu.column]["dim_score"] += dim_signal
            brain.knowledge_base[cu.column]["mea_score"] += mea_signal
            columns_updated += 1

    if columns_updated > 0:
        brain._build_embeddings()

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
