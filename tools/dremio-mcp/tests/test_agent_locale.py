#
#  Copyright (C) 2017-2025 Dremio Corporation
#

import os

import pytest

from dremioai.agent.locale import (
    agent_response_locale,
    answer_looks_english_not_vietnamese,
    finalize_system_message,
    finalize_user_message,
    hitl_metadata_message,
    user_facing_language_clause,
    user_prefers_vietnamese,
)


@pytest.fixture(autouse=True)
def _clear_locale_env(monkeypatch):
    monkeypatch.delenv("AGENT_RESPONSE_LOCALE", raising=False)
    monkeypatch.delenv("LLM_PROVIDER", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)


def test_default_vietnamese_finalize_prompt():
    assert agent_response_locale() == "vi"
    msg = finalize_system_message("top 10 chuyến xe")
    assert "tiếng Việt" in msg
    assert "BẮT BUỘC" in msg
    assert "Summarize the query result" not in msg
    assert "tiếng Việt" in finalize_user_message(
        user_question="top 10 chuyến xe",
        final_sql="SELECT 1",
        result_json="[]",
    )
    assert "Xem lại" in hitl_metadata_message()


def test_openai_finalize_has_no_no_think(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    msg = finalize_system_message("list tables")
    assert "/no_think" not in msg
    assert "tiếng Việt" in msg


def test_auto_detects_english_question(monkeypatch):
    monkeypatch.setenv("AGENT_RESPONSE_LOCALE", "auto")
    assert not user_prefers_vietnamese("top 10 trips by amount")
    assert "English" in user_facing_language_clause("top 10 trips")


def test_detects_english_finalize_leakage():
    en = (
        "The query result shows the top 10 trips with the highest fare amounts "
        "from the dataset."
    )
    assert answer_looks_english_not_vietnamese(en)
    assert not answer_looks_english_not_vietnamese(
        "Kết quả truy vấn cho thấy 10 chuyến có fare_amount cao nhất."
    )


def test_finalize_retry_prompt_has_vietnamese_note():
    msg = finalize_system_message("top 10", retry=True)
    assert "tiếng Anh" in msg
