"""Regression tests for chat intent and SQL generation safeguards."""

from __future__ import annotations

from dremioai.agent.nodes.discovery import user_asks_for_schema_description
from dremioai.agent.nodes.guardrail import _looks_like_general_question
from dremioai.agent.nodes.sql_flow import _schema_has_queryable_fields


def test_real_vietnamese_math_skips_catalog_discovery() -> None:
    assert _looks_like_general_question("1+1 b\u1eb1ng m\u1ea5y")


def test_real_vietnamese_column_question_is_schema_only() -> None:
    question = (
        "nh\u1eefng c\u1ed9t c\u00f3 trong Samples."
        "samples.dremio.com.NYC-taxi-trips.csv"
    )
    assert user_asks_for_schema_description(question)


def test_sql_generation_requires_fields_metadata() -> None:
    schema_text = (
        '{"entityType":"dataset","id":"abc","type":"PHYSICAL_DATASET",'
        '"path":["Samples","samples.dremio.com","NYC-taxi-trips.csv"],'
        '"format":{"type":"Text"}}'
    )
    assert not _schema_has_queryable_fields(schema_text)


def test_sql_generation_allows_real_fields_metadata() -> None:
    schema_text = '{"fields":[{"name":"Category","type":{"name":"VARCHAR"}}]}'
    assert _schema_has_queryable_fields(schema_text)
