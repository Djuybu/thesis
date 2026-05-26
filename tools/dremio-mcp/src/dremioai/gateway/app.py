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

"""FastAPI gateway for the Dremio SQL HITL agent.

Replaces the legacy aichatbot-plugin Java server and the separate langchain-gateway process.
Endpoints are served under ``/aichat/`` so the existing DAC reverse-proxy works unchanged.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Any

_AGENT_TIMINGS_KEY = "__agent_timings__"

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from langgraph.types import Command

from dremioai.agent.graph import build_graph
from dremioai.agent.mcp_client import load_mcp_tools
from dremioai.gateway.config import env, normalize_auth_header
from dremioai.gateway.llm_factory import (
    build_llm,
    default_llm_model,
    llm_api_key_configured,
    resolve_llm_provider,
)
from dremioai.gateway.models import (
    ChatResponse,
    ChatResumeRequest,
    ChatStartRequest,
    ConfigResponse,
    TokenUsage,
)
from dremioai.gateway.execution_result import normalize_execution_result
from dremioai.gateway.run_log import append_agent_run_log
from dremioai.gateway.token_tracking import AgentTokenTracker

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("dremio-sql-agent")

app = FastAPI(title="Dremio SQL Agent Gateway", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _interrupts_to_serializable(result: dict[str, Any]) -> list[dict[str, Any]]:
    intr = result.get("__interrupt__") or []
    out: list[dict[str, Any]] = []
    for item in intr:
        val = getattr(item, "value", item)
        out.append(val if isinstance(val, dict) else {"value": val})
    return out


def _step_timings_in_response() -> bool:
    return env("AGENT_STEP_TIMINGS", "1").strip().lower() not in (
        "0",
        "false",
        "no",
        "off",
    )


def _token_usage_in_response() -> bool:
    return env("AGENT_TOKEN_USAGE", "1").strip().lower() not in (
        "0",
        "false",
        "no",
        "off",
    )


def _token_usage_from_dict(raw: Any) -> TokenUsage | None:
    if not isinstance(raw, dict):
        return None
    return TokenUsage(
        input_tokens=int(raw.get("input_tokens") or 0),
        output_tokens=int(raw.get("output_tokens") or 0),
        total_tokens=int(raw.get("total_tokens") or 0),
    )


def _extract_agent_metrics(
    result: dict[str, Any],
) -> tuple[int | None, dict[str, int] | None, TokenUsage | None, dict[str, TokenUsage] | None]:
    raw = result.pop(_AGENT_TIMINGS_KEY, None)
    if not isinstance(raw, dict):
        return None, None, None, None
    total = raw.get("total")
    steps = raw.get("steps")
    elapsed = int(total) if total is not None else None
    step_map = None
    if _step_timings_in_response() and isinstance(steps, dict):
        step_map = {str(k): int(v) for k, v in steps.items()}

    token_usage = None
    step_token_usage = None
    if _token_usage_in_response():
        token_usage = _token_usage_from_dict(raw.get("tokens"))
        step_raw = raw.get("step_tokens")
        if isinstance(step_raw, dict):
            step_token_usage = {}
            for name, counts in step_raw.items():
                tu = _token_usage_from_dict(counts)
                if tu and (tu.input_tokens or tu.output_tokens or tu.total_tokens):
                    step_token_usage[str(name)] = tu
            if not step_token_usage:
                step_token_usage = None
        if token_usage and not token_usage.total_tokens and not token_usage.input_tokens:
            if not step_token_usage:
                token_usage = None

    return elapsed, step_map, token_usage, step_token_usage


async def _graph_astream_collect(
    graph: Any,
    payload: Any,
    config: dict[str, Any],
    token_tracker: AgentTokenTracker | None = None,
) -> tuple[dict[str, Any], dict[str, int]]:
    """Run the compiled graph and merge streamed updates into one state dict.

    - ``stream_mode=\"values\"`` **does not** emit ``__interrupt__`` chunks (only the
      last state snapshot), so HITL would be invisible to the HTTP layer.
    - ``stream_mode=\"updates\"`` emits ``{\"node\": {partial state}}`` and a final
      ``{\"__interrupt__\": (...)}`` chunk; we fold node payloads into ``accum``
      so ``_result_to_response`` sees flat ``AgentState`` keys (``error``,
      ``assistant_answer``, …) on normal completion.
    """
    accum: dict[str, Any] = {}
    step_ms: dict[str, int] = {}
    interrupt_val: Any = None
    open_node: str | None = None
    t_node = time.perf_counter()

    async for chunk in graph.astream(
        payload,
        config=config,
        stream_mode="updates",
    ):
        t_chunk = time.perf_counter()
        if not isinstance(chunk, dict):
            continue
        intr = chunk.get("__interrupt__")
        if intr:
            interrupt_val = intr
        for key, val in chunk.items():
            if key == "__interrupt__":
                continue
            if open_node is not None and key != open_node:
                step_ms[open_node] = step_ms.get(open_node, 0) + int(
                    (t_chunk - t_node) * 1000
                )
                t_node = t_chunk
            open_node = key
            if token_tracker is not None:
                token_tracker.set_active_node(key)
            if isinstance(val, dict):
                accum.update(val)
            else:
                accum[key] = val
        t_node = time.perf_counter()

    if open_node is not None:
        step_ms[open_node] = step_ms.get(open_node, 0) + int(
            (time.perf_counter() - t_node) * 1000
        )

    out = dict(accum)
    if interrupt_val is not None:
        out["__interrupt__"] = interrupt_val
    return out, step_ms


def _agent_timeout_seconds() -> float | None:
    """Optional wall-clock cap for ``graph.ainvoke`` (unset or 0 = unlimited)."""
    raw = env("AGENT_REQUEST_TIMEOUT_SECONDS", "").strip()
    if not raw:
        return None
    try:
        v = float(raw)
    except ValueError:
        return None
    return v if v > 0 else None


async def _ainvoke_agent(
    graph: Any,
    payload: Any,
    config: dict[str, Any],
    *,
    thread_id: str,
    phase: str,
) -> dict[str, Any]:
    timeout_s = _agent_timeout_seconds()
    logger.info(
        "Agent %s start thread_id=%s timeout_seconds=%s",
        phase,
        thread_id,
        timeout_s if timeout_s is not None else "none",
    )
    token_tracker = AgentTokenTracker()
    run_config = dict(config)
    run_config["callbacks"] = list(config.get("callbacks") or []) + [token_tracker]

    t0 = time.perf_counter()
    try:
        if timeout_s is not None:
            result, step_ms = await asyncio.wait_for(
                _graph_astream_collect(graph, payload, run_config, token_tracker),
                timeout=timeout_s,
            )
        else:
            result, step_ms = await _graph_astream_collect(
                graph, payload, run_config, token_tracker
            )
    except asyncio.TimeoutError as e:
        raise HTTPException(
            status_code=504,
            detail=(
                f"Agent exceeded AGENT_REQUEST_TIMEOUT_SECONDS ({timeout_s}s). "
                "Increase the variable, or complete HITL steps via resume."
            ),
        ) from e
    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    tokens_snap = token_tracker.snapshot()
    result[_AGENT_TIMINGS_KEY] = {
        "total": elapsed_ms,
        "steps": step_ms,
        "tokens": tokens_snap.get("total"),
        "step_tokens": tokens_snap.get("steps"),
    }
    logger.info(
        "Agent %s finished thread_id=%s elapsed_ms=%s",
        phase,
        thread_id,
        elapsed_ms,
    )
    if step_ms:
        logger.info("Agent %s step_timings_ms=%s", phase, step_ms)
    if tokens_snap.get("total", {}).get("total_tokens"):
        logger.info("Agent %s token_usage=%s", phase, tokens_snap.get("total"))
    if tokens_snap.get("steps"):
        logger.info("Agent %s step_token_usage=%s", phase, tokens_snap.get("steps"))
    return result


def _detect_interrupt_node(interrupts: list[dict[str, Any]]) -> str | None:
    if not interrupts:
        return None
    action = interrupts[0].get("action", "")
    if action == "metadata_confirmation":
        return "metadata_confirmation"
    if action == "sql_approval":
        return "refinement"
    return None


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@app.get("/aichat/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "dremio-sql-agent"}


@app.get("/aichat/v1/config", response_model=ConfigResponse)
async def config() -> ConfigResponse:
    mcp_url = env("DREMIO_MCP_URL")
    return ConfigResponse(
        default_model=default_llm_model(),
        llm_provider=resolve_llm_provider(),
        api_key_configured=llm_api_key_configured(),
        mcp_configured=bool(mcp_url),
    )


@app.post("/aichat/v1/chat", response_model=ChatResponse)
async def chat_start(http_request: Request, body: ChatStartRequest) -> ChatResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")

    model_name = body.model or default_llm_model()
    try:
        llm = build_llm(model_name)
    except ValueError as e:
        raise HTTPException(status_code=503, detail=str(e)) from e

    try:
        mcp_tools = await load_mcp_tools(auth)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to load MCP tools: {e!s}") from e

    graph = build_graph(llm, mcp_tools)
    thread_id = (body.thread_id or "").strip() or f"hitl-{uuid.uuid4()}"
    config: dict[str, Any] = {
        "configurable": {"thread_id": thread_id},
        "recursion_limit": 80,
    }
    initial: dict[str, Any] = {
        "user_question": body.message.strip(),
        "user_context": body.user_context,
        "dremio_auth_header": auth,
    }

    try:
        result = await _ainvoke_agent(
            graph,
            initial,
            config,
            thread_id=thread_id,
            phase="chat_start",
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Agent graph failed: {e!s}") from e

    response = _result_to_response(result, thread_id, model_name)
    append_agent_run_log(
        phase="chat_start",
        response=response,
        graph_state=result,
        user_message=body.message.strip(),
        session_id=body.session_id,
        user_id=body.user_id,
    )
    return response


@app.post("/aichat/ask", response_model=ChatResponse, include_in_schema=False)
async def chat_legacy_ask(http_request: Request, body: ChatStartRequest) -> ChatResponse:
    """Alias for clients still calling the legacy Java plugin path ``/aichat/ask``."""
    return await chat_start(http_request, body)


@app.post("/aichat/v1/chat/resume", response_model=ChatResponse)
async def chat_resume(http_request: Request, body: ChatResumeRequest) -> ChatResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")

    model_name = default_llm_model()
    try:
        llm = build_llm(model_name)
    except ValueError as e:
        raise HTTPException(status_code=503, detail=str(e)) from e

    try:
        mcp_tools = await load_mcp_tools(auth)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to load MCP tools: {e!s}") from e

    graph = build_graph(llm, mcp_tools)
    thread_id = body.thread_id.strip()
    config: dict[str, Any] = {
        "configurable": {"thread_id": thread_id},
        "recursion_limit": 80,
    }

    resume_val = _build_resume_payload(body)

    try:
        result = await _ainvoke_agent(
            graph,
            Command(resume=resume_val, update={"dremio_auth_header": auth}),
            config,
            thread_id=thread_id,
            phase="chat_resume",
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Resume failed: {e!s}") from e

    response = _result_to_response(result, thread_id, model_name)
    append_agent_run_log(
        phase="chat_resume",
        response=response,
        graph_state=result,
        resume_action=body.action,
        resume_payload=body.payload,
    )
    return response


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_resume_payload(body: ChatResumeRequest) -> Any:
    if body.action == "approve":
        return {"approved": True}
    if body.action == "reject":
        return {"approved": False}
    if body.action == "edit" and body.payload:
        return {"approved": True, "sql": body.payload.get("sql", "")}
    return {"approved": False}


def _metrics_kwargs(
    elapsed_ms: int | None,
    step_timings_ms: dict[str, int] | None,
    token_usage: TokenUsage | None,
    step_token_usage: dict[str, TokenUsage] | None,
) -> dict[str, Any]:
    return {
        "elapsed_ms": elapsed_ms,
        "step_timings_ms": step_timings_ms,
        "token_usage": token_usage,
        "step_token_usage": step_token_usage,
    }


def _result_to_response(
    result: dict[str, Any], thread_id: str, model_name: str
) -> ChatResponse:
    elapsed_ms, step_timings_ms, token_usage, step_token_usage = _extract_agent_metrics(
        result
    )
    metrics = _metrics_kwargs(
        elapsed_ms, step_timings_ms, token_usage, step_token_usage
    )

    if result.get("__interrupt__"):
        interrupts = _interrupts_to_serializable(result)
        node = _detect_interrupt_node(interrupts)
        return ChatResponse(
            status="interrupted",
            thread_id=thread_id,
            model=model_name,
            node=node,
            interrupt=interrupts[0] if len(interrupts) == 1 else interrupts,
            **metrics,
        )

    err = result.get("error")
    ans = result.get("assistant_answer")
    if err and not ans:
        return ChatResponse(
            status="error",
            thread_id=thread_id,
            model=model_name,
            error=str(err),
            **metrics,
        )

    return ChatResponse(
        status="completed",
        thread_id=thread_id,
        model=model_name,
        answer=ans,
        execution_result=normalize_execution_result(result.get("execution_raw")),
        error=str(err) if err else None,
        **metrics,
    )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    import sys

    import uvicorn

    host = env("GATEWAY_HOST", "127.0.0.1")
    port = int(env("GATEWAY_PORT", "9292"))
    reload = "--reload" in sys.argv
    uvicorn.run(
        "dremioai.gateway.app:app",
        host=host,
        port=port,
        reload=reload,
    )


if __name__ == "__main__":
    main()
