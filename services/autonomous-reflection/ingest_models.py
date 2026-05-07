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

from __future__ import annotations

from pydantic import BaseModel, Field, model_validator
from typing import List


class ColumnUsageCounts(BaseModel):
    column: str
    projectionCount: int = Field(ge=0, default=0)
    filterCount: int = Field(ge=0, default=0)
    groupByCount: int = Field(ge=0, default=0)
    aggregateCount: int = Field(ge=0, default=0)


class DatasetUsage(BaseModel):
    datasetPath: List[str] = Field(min_length=1)
    columnUsage: List[ColumnUsageCounts] = Field(min_length=1)


class IngestRequest(BaseModel):
    batchId: str = Field(min_length=1)
    windowStartEpochMs: int = Field(ge=0)
    windowEndEpochMs: int = Field(ge=0)
    datasets: List[DatasetUsage] = Field(min_length=1)

    @model_validator(mode="after")
    def _window_order(self) -> "IngestRequest":
        if self.windowStartEpochMs > self.windowEndEpochMs:
            raise ValueError("windowStartEpochMs must be <= windowEndEpochMs")
        return self


class IngestResponse(BaseModel):
    accepted: bool
    datasetsProcessed: int
    columnsUpdated: int
    skippedDuplicate: bool
