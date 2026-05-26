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

"""Append one JSON Lines record per agent HTTP request for offline analysis."""

from __future__ import annotations

import json
import logging
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from dremioai.gateway.config import env
from dremioai.gateway.llm_factory import resolve_llm_provider
from dremioai.gateway.models import ChatResponse, TokenUsage

logger = logging.getLogger("dremio-sql-agent")

_WRITE_LOCK = threading.Lock()

_STATE_EXTRA_KEYS = (
    "user_question",
    "proposed_sql",
    "sql_rationale",
    "error",
)


def run_log_enabled() -> bool:
    return env("AGENT_RUN_LOG", "1").strip().lower() not in (
        "0",
        "false",
        "no",
        "off",
    )


def resolve_run_log_path() -> Path | None:
    """Return the JSONL file path, or None when run logging is disabled."""
    if not run_log_enabled():
        return None
    explicit = env("AGENT_RUN_LOG_PATH", "").strip()
    if explicit:
        return Path(explicit).expanduser()
    log_dir = env("AGENT_RUN_LOG_DIR", "").strip()
    if log_dir:
        return Path(log_dir).expanduser() / "agent-runs.jsonl"
    return None


def _token_usage_dict(tu: TokenUsage | None) -> dict[str, int] | None:
    if tu is None:
        return None
    return tu.model_dump()


def _step_token_usage_dict(
    raw: dict[str, TokenUsage] | None,
) -> dict[str, dict[str, int]] | None:
    if not raw:
        return None
    return {name: tu.model_dump() for name, tu in raw.items()}


def _json_default(obj: Any) -> Any:
    if isinstance(obj, BaseModel):
        return obj.model_dump()
    if isinstance(obj, Path):
        return str(obj)
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def _state_extras(graph_state: dict[str, Any] | None) -> dict[str, Any]:
    if not graph_state:
        return {}
    out: dict[str, Any] = {}
    for key in _STATE_EXTRA_KEYS:
        val = graph_state.get(key)
        if val is not None and val != "":
            out[key] = val
    return out


def build_run_log_record(
    *,
    phase: str,
    response: ChatResponse,
    graph_state: dict[str, Any] | None = None,
    user_message: str | None = None,
    session_id: str | None = None,
    user_id: str | None = None,
    resume_action: str | None = None,
    resume_payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Assemble one JSON-serializable dict for a single chat or resume request."""
    record: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "phase": phase,
        "llm_provider": resolve_llm_provider(),
        "thread_id": response.thread_id,
        "session_id": session_id,
        "user_id": user_id,
        "model": response.model,
        "status": response.status,
        "node": response.node,
        "elapsed_ms": response.elapsed_ms,
        "step_timings_ms": response.step_timings_ms,
        "token_usage": _token_usage_dict(response.token_usage),
        "step_token_usage": _step_token_usage_dict(response.step_token_usage),
        "user_message": user_message,
        "resume_action": resume_action,
        "resume_payload": resume_payload,
        "answer": response.answer,
        "error": response.error,
        "execution_result": response.execution_result,
        "interrupt": response.interrupt,
    }
    record.update(_state_extras(graph_state))
    return record


def append_agent_run_log(
    *,
    phase: str,
    response: ChatResponse,
    graph_state: dict[str, Any] | None = None,
    user_message: str | None = None,
    session_id: str | None = None,
    user_id: str | None = None,
    resume_action: str | None = None,
    resume_payload: dict[str, Any] | None = None,
) -> Path | None:
    """Append one JSONL line; returns the log file path when written."""
    path = resolve_run_log_path()
    if path is None:
        return None

    record = build_run_log_record(
        phase=phase,
        response=response,
        graph_state=graph_state,
        user_message=user_message,
        session_id=session_id,
        user_id=user_id,
        resume_action=resume_action,
        resume_payload=resume_payload,
    )
    line = json.dumps(record, ensure_ascii=False, default=_json_default) + "\n"

    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with _WRITE_LOCK:
            with path.open("a", encoding="utf-8") as fh:
                fh.write(line)
    except OSError as e:
        logger.warning("Agent run log write failed path=%s error=%s", path, e)
        return None

    logger.info("Agent run log appended path=%s phase=%s thread_id=%s", path, phase, response.thread_id)
    return path

