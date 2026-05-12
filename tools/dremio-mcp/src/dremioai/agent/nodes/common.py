from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field

# Debug NDJSON (session 70b267); see .cursor/debug-70b267.log
_AGENT_DEBUG_LOG_PATH = "/home/djuybu/thesis/.cursor/debug-70b267.log"


def structured_llm_timeout_seconds() -> float | None:
    """Cap for Ollama ``with_structured_output`` calls; unset/0 = unlimited.

    Default is 900 seconds. Larger Qwen builds (e.g. qwen3.5:4b) running on
    CPU can take 2-5 minutes to emit JSON conforming to a Pydantic schema;
    a too-small cap surfaces as ``asyncio.TimeoutError`` whose empty ``str()``
    repr produces a silent ``"SQL generation failed: "`` error from
    ``sql_gen_node``. The default LLM ``qwen2.5:3b`` typically completes in
    10-30s, so 900s is purely defensive.
    """
    raw = os.environ.get("AGENT_STRUCTURED_LLM_TIMEOUT_SECONDS", "900").strip().lower()
    if not raw or raw in ("0", "false", "no", "off", "none"):
        return None
    try:
        v = float(raw)
    except ValueError:
        return 900.0
    return v if v > 0 else None


def agent_debug_ndjson(
    hypothesis_id: str,
    location: str,
    message: str,
    data: dict[str, Any],
) -> None:
    # #region agent log
    try:
        payload = {
            "sessionId": "70b267",
            "timestamp": int(time.time() * 1000),
            "hypothesisId": hypothesis_id,
            "location": location,
            "message": message,
            "data": data,
        }
        with Path(_AGENT_DEBUG_LOG_PATH).open("a", encoding="utf-8") as df:
            df.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError:
        pass
    # #endregion

logger = logging.getLogger("dremio-sql-agent")


def _agent_trace_on() -> bool:
    """Set AGENT_TRACE_LOG=0 to silence step/tool logs (default: on)."""
    v = os.environ.get("AGENT_TRACE_LOG", "1").strip().lower()
    return v not in ("0", "false", "no", "off")


def _trace(msg: str, *args: Any) -> None:
    if _agent_trace_on():
        logger.info("[agent] " + msg, *args)


def _agent_trace_verbose_on() -> bool:
    """Set AGENT_TRACE_VERBOSE=0 to hide stage internals (default: on)."""
    v = os.environ.get("AGENT_TRACE_VERBOSE", "1").strip().lower()
    return v not in ("0", "false", "no", "off")


def _agent_trace_llm_content_on() -> bool:
    """Set AGENT_TRACE_LLM_CONTENT=1 to log prompt/response excerpts."""
    v = os.environ.get("AGENT_TRACE_LLM_CONTENT", "0").strip().lower()
    return v in ("1", "true", "yes", "on")


def _trace_verbose(msg: str, *args: Any) -> None:
    if _agent_trace_on() and _agent_trace_verbose_on():
        logger.info("[agent][verbose] " + msg, *args)


def _preview(text: str | None, n: int = 240) -> str:
    if not text:
        return ""
    s = str(text).replace("\n", " ").strip()
    return s if len(s) <= n else s[: n - 1] + "..."


def _json_compact(obj: Any, max_len: int = 12000) -> str:
    """Pretty-print JSON-compatible payloads without double-encoding pre-serialized strings."""
    try:
        if isinstance(obj, str):
            stripped = obj.strip()
            if stripped and stripped[0] in "{[":
                try:
                    parsed = json.loads(stripped)
                    s = json.dumps(parsed, default=str, ensure_ascii=False, indent=2)
                except (json.JSONDecodeError, TypeError):
                    s = obj
            else:
                s = obj
        else:
            s = json.dumps(obj, default=str, ensure_ascii=False, indent=2)
    except TypeError:
        s = str(obj)
    return s[:max_len] + "\n...(truncated)" if len(s) > max_len else s


class TablePick(BaseModel):
    """Best Dremio table or view to answer the question."""

    table_fqn: str = Field(
        ...,
        description='Fully-qualified Dremio identifier, e.g. "source"."folder"."table"',
    )
    rationale: str = Field(default="", description="Why this table matches the user question")


class SqlProposal(BaseModel):
    proposed_sql: str = Field(..., description="A single SELECT query for Dremio")
    rationale: str = Field(default="", description="Brief note on filters and columns")
