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
import uuid
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from langchain_ollama import ChatOllama
from langgraph.types import Command

from dremioai.agent.graph import build_graph
from dremioai.agent.mcp_client import load_mcp_tools
from dremioai.gateway.config import DEFAULT_OLLAMA_MODEL, env, normalize_auth_header
from dremioai.gateway.models import (
    ChatResponse,
    ChatResumeRequest,
    ChatStartRequest,
    ConfigResponse,
)

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


_llm_cache: dict[str, Any] = {}


def _build_llm(model_name: str | None = None, temperature: float = 0.0) -> Any:
    """Return ChatOllama (often ``bind(stream=False)``), cached per (model, base, temperature).

    ``langchain_ollama.ChatOllama`` defaults ``stream=True``. Aggregation of SSE chunks
    can hang with some models (e.g. Qwen thinking builds) after HTTP 200. Non-streaming
    chat avoids that. Enable streaming via ``OLLAMA_STREAM=1``.
    """
    model = model_name or env("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL)
    base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    stream_on = env("OLLAMA_STREAM", "false").strip().lower() in ("1", "true", "yes", "on")
    cache_key = f"{model}|{base}|{temperature}|{stream_on}"
    if cache_key in _llm_cache:
        return _llm_cache[cache_key]
    llm = ChatOllama(model=model, base_url=base, temperature=temperature)
    result = llm if stream_on else llm.bind(stream=False)
    _llm_cache[cache_key] = result
    return result


def _interrupts_to_serializable(result: dict[str, Any]) -> list[dict[str, Any]]:
    intr = result.get("__interrupt__") or []
    out: list[dict[str, Any]] = []
    for item in intr:
        val = getattr(item, "value", item)
        out.append(val if isinstance(val, dict) else {"value": val})
    return out


async def _graph_astream_collect(
    graph: Any, payload: Any, config: dict[str, Any]
) -> dict[str, Any]:
    """Run the compiled graph and merge streamed updates into one state dict.

    - ``stream_mode=\"values\"`` **does not** emit ``__interrupt__`` chunks (only the
      last state snapshot), so HITL would be invisible to the HTTP layer.
    - ``stream_mode=\"updates\"`` emits ``{\"node\": {partial state}}`` and a final
      ``{\"__interrupt__\": (...)}`` chunk; we fold node payloads into ``accum``
      so ``_result_to_response`` sees flat ``AgentState`` keys (``error``,
      ``assistant_answer``, …) on normal completion.
    """
    accum: dict[str, Any] = {}
    interrupt_val: Any = None
    async for chunk in graph.astream(
        payload,
        config=config,
        stream_mode="updates",
    ):
        if not isinstance(chunk, dict):
            continue
        intr = chunk.get("__interrupt__")
        if intr:
            interrupt_val = intr
        for key, val in chunk.items():
            if key == "__interrupt__":
                continue
            if isinstance(val, dict):
                accum.update(val)
            else:
                accum[key] = val
    out = dict(accum)
    if interrupt_val is not None:
        out["__interrupt__"] = interrupt_val
    return out


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
    try:
        if timeout_s is not None:
            result = await asyncio.wait_for(
                _graph_astream_collect(graph, payload, config),
                timeout=timeout_s,
            )
        else:
            result = await _graph_astream_collect(graph, payload, config)
    except asyncio.TimeoutError as e:
        raise HTTPException(
            status_code=504,
            detail=(
                f"Agent exceeded AGENT_REQUEST_TIMEOUT_SECONDS ({timeout_s}s). "
                "Increase the variable, or complete HITL steps via resume."
            ),
        ) from e
    logger.info("Agent %s finished thread_id=%s", phase, thread_id)
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
        default_model=env("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL),
        mcp_configured=bool(mcp_url),
    )


@app.post("/aichat/v1/chat", response_model=ChatResponse)
async def chat_start(http_request: Request, body: ChatStartRequest) -> ChatResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")

    model_name = body.model or env("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL)
    llm = _build_llm(model_name)

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

    return _result_to_response(result, thread_id, model_name)


@app.post("/aichat/ask", response_model=ChatResponse, include_in_schema=False)
async def chat_legacy_ask(http_request: Request, body: ChatStartRequest) -> ChatResponse:
    """Alias for clients still calling the legacy Java plugin path ``/aichat/ask``."""
    return await chat_start(http_request, body)


@app.post("/aichat/v1/chat/resume", response_model=ChatResponse)
async def chat_resume(http_request: Request, body: ChatResumeRequest) -> ChatResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")

    model_name = env("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL)
    llm = _build_llm(model_name)

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

    return _result_to_response(result, thread_id, model_name)


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


def _result_to_response(
    result: dict[str, Any], thread_id: str, model_name: str
) -> ChatResponse:
    if result.get("__interrupt__"):
        interrupts = _interrupts_to_serializable(result)
        node = _detect_interrupt_node(interrupts)
        return ChatResponse(
            status="interrupted",
            thread_id=thread_id,
            model=model_name,
            node=node,
            interrupt=interrupts[0] if len(interrupts) == 1 else interrupts,
        )

    err = result.get("error")
    ans = result.get("assistant_answer")
    if err and not ans:
        return ChatResponse(
            status="error",
            thread_id=thread_id,
            model=model_name,
            error=str(err),
        )

    return ChatResponse(
        status="completed",
        thread_id=thread_id,
        model=model_name,
        answer=ans,
        execution_result=result.get("execution_raw"),
        error=str(err) if err else None,
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
