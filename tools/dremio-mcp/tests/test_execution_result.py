#
#  Copyright (C) 2017-2025 Dremio Corporation
#

from dremioai.gateway.execution_result import normalize_execution_result


def test_normalize_result_wrapper_dict():
    raw = {"result": [{"a": 1}, {"a": 2}]}
    assert normalize_execution_result(raw) == [{"a": 1}, {"a": 2}]


def test_normalize_json_string():
    raw = '{"result":[{"pickup_datetime":"x","fare_amount":"1"}]}'
    rows = normalize_execution_result(raw)
    assert rows and rows[0]["fare_amount"] == "1"


def test_normalize_plain_array():
    assert normalize_execution_result([{"x": 1}]) == [{"x": 1}]


def test_error_dict_returns_none():
    assert normalize_execution_result({"error": "boom"}) is None
