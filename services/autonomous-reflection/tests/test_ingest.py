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
Tests for POST /knowledge/ingest:
  - 401 khi token sai
  - 409-equivalent (skippedDuplicate) khi batchId trùng
  - 200 khi ingest hợp lệ, knowledge thay đổi đúng
  - /predict/schema vẫn hoạt động sau ingest
"""

from __future__ import annotations

import os
import sys
import uuid
from pathlib import Path

import pytest

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import ingest_service  # noqa: E402
import main as ar_main  # noqa: E402


def _ingest_body(batch_id: str | None = None, columns=None):
    return {
        "batchId": batch_id or str(uuid.uuid4()),
        "windowStartEpochMs": 1715000000000,
        "windowEndEpochMs": 1715000300000,
        "datasets": [
            {
                "datasetPath": ["Sales", "orders"],
                "columnUsage": columns
                or [
                    {
                        "column": "order_id",
                        "projectionCount": 50,
                        "filterCount": 10,
                        "groupByCount": 5,
                        "aggregateCount": 0,
                    },
                    {
                        "column": "revenue",
                        "projectionCount": 30,
                        "filterCount": 0,
                        "groupByCount": 0,
                        "aggregateCount": 40,
                    },
                ],
            }
        ],
    }


@pytest.fixture(autouse=True)
def _clear_batches():
    ingest_service.clear_processed_batches()
    yield
    ingest_service.clear_processed_batches()


@pytest.fixture
def ingest_client(monkeypatch, reflection_brain_real_class):
    """TestClient with a real (patched-encoder) brain and token auth enabled."""
    from fastapi.testclient import TestClient

    monkeypatch.setattr(ar_main, "INGEST_TOKEN", "test-secret-42")
    with TestClient(ar_main.app) as client:
        ar_main.ml_models["brain"] = reflection_brain_real_class
        yield client


def test_ingest_returns_401_when_token_wrong(ingest_client):
    r = ingest_client.post(
        "/knowledge/ingest",
        json=_ingest_body(),
        headers={"X-AR-Ingest-Token": "wrong-token"},
    )
    assert r.status_code == 401


def test_ingest_returns_401_when_token_missing(ingest_client):
    r = ingest_client.post("/knowledge/ingest", json=_ingest_body())
    assert r.status_code == 401


def test_ingest_returns_200_and_updates_knowledge(ingest_client):
    r = ingest_client.post(
        "/knowledge/ingest",
        json=_ingest_body(),
        headers={"X-AR-Ingest-Token": "test-secret-42"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["accepted"] is True
    assert data["datasetsProcessed"] == 1
    assert data["columnsUpdated"] == 2
    assert data["skippedDuplicate"] is False

    brain = ar_main.ml_models["brain"]
    assert "order_id" in brain.knowledge_base
    assert brain.knowledge_base["order_id"]["dim_score"] == 65  # 50+10+5
    assert brain.knowledge_base["order_id"]["mea_score"] == 0

    assert "revenue" in brain.knowledge_base
    assert brain.knowledge_base["revenue"]["dim_score"] == 30
    assert brain.knowledge_base["revenue"]["mea_score"] == 40


def test_ingest_duplicate_batch_is_skipped(ingest_client):
    bid = "dup-batch-001"
    headers = {"X-AR-Ingest-Token": "test-secret-42"}

    r1 = ingest_client.post(
        "/knowledge/ingest", json=_ingest_body(batch_id=bid), headers=headers
    )
    assert r1.status_code == 200
    assert r1.json()["skippedDuplicate"] is False

    r2 = ingest_client.post(
        "/knowledge/ingest", json=_ingest_body(batch_id=bid), headers=headers
    )
    assert r2.status_code == 200
    assert r2.json()["skippedDuplicate"] is True
    assert r2.json()["columnsUpdated"] == 0


def test_ingest_rejects_invalid_window(ingest_client):
    body = _ingest_body()
    body["windowStartEpochMs"] = 9999999999999
    body["windowEndEpochMs"] = 1000000000000
    r = ingest_client.post(
        "/knowledge/ingest",
        json=body,
        headers={"X-AR-Ingest-Token": "test-secret-42"},
    )
    assert r.status_code == 422


def test_predict_schema_still_works_after_ingest(ingest_client):
    """Ingest dimension-heavy usage for order_id, then predict: should be Dimension."""
    headers = {"X-AR-Ingest-Token": "test-secret-42"}
    ingest_client.post(
        "/knowledge/ingest",
        json=_ingest_body(
            columns=[
                {
                    "column": "order_id",
                    "projectionCount": 100,
                    "filterCount": 0,
                    "groupByCount": 50,
                    "aggregateCount": 0,
                },
                {
                    "column": "revenue",
                    "projectionCount": 0,
                    "filterCount": 0,
                    "groupByCount": 0,
                    "aggregateCount": 80,
                },
            ]
        ),
        headers=headers,
    )

    predict_body = {
        "datasetPath": ["Sales", "orders"],
        "columns": [
            {"name": "order_id", "type": "VARCHAR"},
            {"name": "revenue", "type": "DOUBLE"},
        ],
    }
    r = ingest_client.post("/predict/schema", json=predict_body)
    assert r.status_code == 200
    data = r.json()
    assert "order_id" in data["dimensions"]
    assert any(m["name"] == "revenue" for m in data["measures"])


def test_ingest_returns_503_when_no_model(monkeypatch):
    from fastapi.testclient import TestClient

    monkeypatch.setattr(ar_main, "INGEST_TOKEN", "")
    with TestClient(ar_main.app) as client:
        ar_main.ml_models["brain"] = None
        r = client.post("/knowledge/ingest", json=_ingest_body())
        assert r.status_code == 503
