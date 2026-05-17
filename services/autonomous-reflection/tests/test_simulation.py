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
Giả lập kiểm thử cho Autonomous Reflection service (FastAPI trong main.py).

- Kịch bản 1: gửi schema, so khớp dimension / measure (RuleBrain = nhãn đúng theo tên cột).
- Kịch bản 2: mô phỏng workload truy vấn (tập cột mỗi lần query) và thu gọn reflection
  theo tần suất — giữ cột hay dùng, bỏ cột không xuất hiện trong workload.

Chạy:

  cd services/autonomous-reflection && python -m pytest tests/test_simulation.py -v

RuleBrain cố ý cố định dim/measure để không phụ thuộc tải model HF. Để kiểm thử semantic
thật, huấn luyện ReflectionBrain + sentence-transformers ngoài pytest hoặc bật test tích hợp riêng.
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path
from typing import List, Mapping, Sequence, Set, Tuple

import pytest

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import main as ar_main  # noqa: E402
from conftest import RuleBrain  # noqa: E402


def _labeled_json(column: str, type_label: str) -> str:
    return '{"column": "%s", "type": "%s"}' % (column, type_label)


def _post_predict(
    client,
    columns: List[Tuple[str, str]],
    dataset_path: List[str] | None = None,
) -> dict:
    body = {
        "datasetPath": dataset_path or ["test", "space", "sales"],
        "columns": [{"name": n, "type": t} for n, t in columns],
    }
    r = client.post("/predict/schema", json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _usage_from_queries(query_column_sets: Sequence[Sequence[str]]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for cols in query_column_sets:
        for c in cols:
            counts[c] += 1
    return counts


def _prune_reflection_by_usage(
    dimensions: List[str],
    measures: List[Mapping],
    usage: Mapping[str, int],
    *,
    min_fraction_of_max: float = 0.15,
) -> Tuple[Set[str], Set[str]]:
    """
    Mô phỏng reflection tiếp theo: chỉ giữ cột có tần suất trong workload đủ cao
    so với cột được query nhiều nhất. Cột không có trong usage bị loại.
    """
    if not usage:
        return set(dimensions), {m["name"] for m in measures}
    max_u = max(usage.values())
    threshold = max(1, int(max_u * min_fraction_of_max))

    def keep(name: str) -> bool:
        return usage.get(name, 0) >= threshold

    dim_kept = {d for d in dimensions if keep(d)}
    mea_kept = {m["name"] for m in measures if keep(m["name"])}
    return dim_kept, mea_kept


def test_varchar_column_as_measure_gets_no_sum(client_with_rule_brain) -> None:
    """
    Semantic model có thể gợi ý Measure cho cột chữ (vd. STATION); Dremio không cho SUM trên CHARACTER.
    """
    ar_main.ml_models["brain"] = RuleBrain(set(), {"STATION"})
    data = _post_predict(
        client_with_rule_brain,
        [("STATION", "VARCHAR")],
        dataset_path=["Samples", "samples.dremio.com", "SF weather 2018-2019.csv"],
    )
    m = next(x for x in data["measures"] if x["name"] == "STATION")
    assert "SUM" not in m["aggregations"], m
    assert "AVG" not in m["aggregations"], m
    assert m["aggregations"] == ["COUNT"], m


def test_schema_predict_dimension_vs_measure(client_with_rule_brain) -> None:
    """POST /predict/schema: dim/measure tách đúng theo nhãn đã học (giả lập RuleBrain)."""
    columns = [
        ("order_id", "VARCHAR"),
        ("customer_name", "VARCHAR"),
        ("order_date", "TIMESTAMP"),
        ("revenue_usd", "DOUBLE"),
        ("tax_amount", "DECIMAL"),
        ("discount_pct", "DOUBLE"),
    ]
    data = _post_predict(client_with_rule_brain, columns)
    dims = set(data["dimensions"])
    measure_names = {m["name"] for m in data["measures"]}

    assert {"order_id", "customer_name", "order_date"}.issubset(dims), data
    assert {"revenue_usd", "tax_amount", "discount_pct"}.issubset(measure_names), data

    by_col = {d["column"]: d["suggested_type"] for d in data["details"]}
    for c in ("order_id", "customer_name", "order_date"):
        assert "imension" in by_col[c] or by_col[c] == "Dimension", by_col
    for c in ("revenue_usd", "tax_amount", "discount_pct"):
        assert "easure" in by_col[c] or by_col[c] == "Measure", by_col


def test_knowledge_simulation_queries_prune_unused_columns(client_with_rule_brain) -> None:
    """
    Giả lập danh sách truy vấn (mỗi phần tử = tập cột chạm trong query).
    Reflection thu gọn phải vẫn chứa cột hay dùng; cột không có trong workload bị loại.
    """
    columns = [
        ("order_id", "VARCHAR"),
        ("customer_name", "VARCHAR"),
        ("order_date", "TIMESTAMP"),
        ("revenue_usd", "DOUBLE"),
        ("tax_amount", "DECIMAL"),
        ("discount_pct", "DOUBLE"),
    ]

    before = _post_predict(client_with_rule_brain, columns)
    dim_before = set(before["dimensions"])
    mea_before = {m["name"] for m in before["measures"]}
    assert len(dim_before) >= 3 and len(mea_before) >= 3

    queries: List[List[str]] = (
        [["order_id", "revenue_usd"]] * 20
        + [["order_id", "revenue_usd", "discount_pct"]] * 5
    )
    usage = _usage_from_queries(queries)

    dim_after, mea_after = _prune_reflection_by_usage(
        before["dimensions"],
        before["measures"],
        usage,
        min_fraction_of_max=0.15,
    )

    assert "order_id" in dim_after
    assert "revenue_usd" in mea_after

    assert "tax_amount" not in mea_after
    assert "customer_name" not in dim_after
    assert "order_date" not in dim_after

    assert len(dim_after) + len(mea_after) < len(dim_before) + len(mea_before)


def test_brain_train_from_queries_updates_measure_bias(reflection_brain_real_class) -> None:
    """Nhiều nhãn measure hơn dimension cho cùng một cột → predict_reflection = Measure."""
    col = "ambiguous_amount"
    brain = reflection_brain_real_class
    brain.train_from_labeled_data(
        [
            _labeled_json(col, "dimension"),
            _labeled_json(col, "dimension"),
            _labeled_json(col, "measure"),
            _labeled_json(col, "measure"),
            _labeled_json(col, "measure"),
        ]
    )
    df = brain.predict_reflection([col])
    row = df.iloc[0]
    assert row["suggested_type"] == "Measure", row.to_dict()


def test_fallback_when_no_model() -> None:
    """Sau lifespan, gán brain=None để buộc nhánh heuristic trong predict_schema."""
    pytest.importorskip("fastapi.testclient")
    from fastapi.testclient import TestClient

    with TestClient(ar_main.app) as client:
        ar_main.ml_models["brain"] = None
        data = _post_predict(
            client,
            [
                ("region", "VARCHAR"),
                ("total_sales", "DOUBLE"),
            ],
        )
    assert "region" in data["dimensions"]
    assert any(m["name"] == "total_sales" for m in data["measures"])
