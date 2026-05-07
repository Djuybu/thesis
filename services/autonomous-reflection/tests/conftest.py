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

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import main as ar_main  # noqa: E402


class OrthoEncoder:
    """Encoder cố định theo tên cột — không tải Hugging Face, phù hợp CI/sandbox."""

    def __init__(self, dim: int = 64):
        self.dim = dim
        self._vec: dict[str, np.ndarray] = {}

    def _vector_for(self, text: str) -> np.ndarray:
        if text not in self._vec:
            rng = np.random.RandomState(abs(hash(text)) % (2**32))
            v = rng.randn(self.dim).astype(np.float32)
            v /= np.linalg.norm(v) + 1e-9
            self._vec[text] = v
        return self._vec[text]

    def encode(self, texts, **kwargs):
        single = isinstance(texts, str)
        if single:
            texts = [texts]
        mat = np.stack([self._vector_for(t) for t in texts])
        return mat[0] if single else mat


class RuleBrain:
    """Brain theo luật: khớp đúng tên cột với tập dimension/measure đã học."""

    def __init__(self, dimensions: set[str], measures: set[str]):
        self._dimensions = dimensions
        self._measures = measures

    def predict_reflection(self, col_names: list[str]) -> pd.DataFrame:
        rows = []
        for c in col_names:
            if c in self._dimensions:
                st, sim, matched = "Dimension", 0.99, c
            elif c in self._measures:
                st, sim, matched = "Measure", 0.99, c
            else:
                st, sim, matched = "None", 0.0, "None"
            rows.append(
                {
                    "column": c,
                    "suggested_type": st,
                    "similarity": sim,
                    "matched_with": matched,
                }
            )
        return pd.DataFrame(rows)


@pytest.fixture
def dimensions_set() -> set[str]:
    return {
        "order_id",
        "customer_name",
        "order_date",
    }


@pytest.fixture
def measures_set() -> set[str]:
    return {"revenue_usd", "tax_amount", "discount_pct"}


@pytest.fixture
def client_with_rule_brain(dimensions_set: set[str], measures_set: set[str]):
    pytest.importorskip("fastapi.testclient")
    from fastapi.testclient import TestClient

    with TestClient(ar_main.app) as client:
        ar_main.ml_models["brain"] = RuleBrain(dimensions_set, measures_set)
        yield client


@pytest.fixture
def reflection_brain_real_class(monkeypatch: pytest.MonkeyPatch) -> ar_main.ReflectionBrain:
    """ReflectionBrain thật nhưng dùng OrthoEncoder thay SentenceTransformer."""

    def patched_init(self, model_name: str = "all-MiniLM-L6-v2"):
        self.encoder = OrthoEncoder()
        self.knowledge_base = {}
        self.threshold = 0.7

    monkeypatch.setattr(ar_main.ReflectionBrain, "__init__", patched_init)
    return ar_main.ReflectionBrain()
