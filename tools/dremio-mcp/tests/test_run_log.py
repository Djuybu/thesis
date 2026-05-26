#
#  Copyright (C) 2017-2025 Dremio Corporation
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

import json
import os
from pathlib import Path

import pytest

from dremioai.gateway.models import ChatResponse, TokenUsage
from dremioai.gateway.run_log import (
    append_agent_run_log,
    build_run_log_record,
    resolve_run_log_path,
    run_log_enabled,
)


@pytest.fixture(autouse=True)
def _clear_run_log_env(monkeypatch):
    for key in ("AGENT_RUN_LOG", "AGENT_RUN_LOG_PATH", "AGENT_RUN_LOG_DIR"):
        monkeypatch.delenv(key, raising=False)


def test_run_log_disabled():
    os.environ["AGENT_RUN_LOG"] = "0"
    assert not run_log_enabled()
    assert resolve_run_log_path() is None


def test_resolve_path_from_explicit(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_RUN_LOG_PATH", str(tmp_path / "runs.jsonl"))
    assert resolve_run_log_path() == tmp_path / "runs.jsonl"


def test_append_writes_jsonl(tmp_path, monkeypatch):
    log_file = tmp_path / "agent-runs.jsonl"
    monkeypatch.setenv("AGENT_RUN_LOG_PATH", str(log_file))
    monkeypatch.setenv("LLM_PROVIDER", "ollama")

    resp = ChatResponse(
        status="completed",
        thread_id="hitl-abc",
        model="qwen2.5:3b",
        answer="Kết quả",
        execution_result={"result": [{"id": 1}]},
        elapsed_ms=1200,
        step_timings_ms={"sql_gen": 800},
        token_usage=TokenUsage(input_tokens=10, output_tokens=5, total_tokens=15),
    )
    written = append_agent_run_log(
        phase="chat_start",
        response=resp,
        graph_state={"user_question": "top 10", "proposed_sql": "SELECT 1"},
        user_message="top 10",
        session_id="sess-1",
    )
    assert written == log_file
    lines = log_file.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 1
    row = json.loads(lines[0])
    assert row["phase"] == "chat_start"
    assert row["elapsed_ms"] == 1200
    assert row["token_usage"]["total_tokens"] == 15
    assert row["user_message"] == "top 10"
    assert row["user_question"] == "top 10"
    assert row["proposed_sql"] == "SELECT 1"
    assert row["execution_result"] == {"result": [{"id": 1}]}


def test_build_record_resume_fields(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    resp = ChatResponse(
        status="interrupted",
        thread_id="hitl-x",
        model="gpt-4o-mini",
        node="refinement",
        elapsed_ms=500,
    )
    rec = build_run_log_record(
        phase="chat_resume",
        response=resp,
        resume_action="approve",
    )
    assert rec["resume_action"] == "approve"
    assert rec["llm_provider"] == "openai"
