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

"""HTTP gateway: LangGraph ReAct agent + Ollama (Qwen, …) + Dremio MCP via aichatbot mcp-proxy."""

from __future__ import annotations

import logging
import os
import asyncio
from datetime import timedelta
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent
from pydantic import BaseModel, Field

def _env(name: str, default: str) -> str:
    v = os.environ.get(name)
    return v.strip() if v and v.strip() else default


def _normalize_auth_header(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    if s.lower().startswith("bearer "):
        return s
    return f"Bearer {s}"


def _message_text(msg: BaseMessage) -> str:
    c = msg.content
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        parts: list[str] = []
        for block in c:
            if isinstance(block, dict):
                if block.get("type") == "text" and "text" in block:
                    parts.append(str(block["text"]))
            elif isinstance(block, str):
                parts.append(block)
        return "\n".join(parts) if parts else str(c)
    return str(c)


def _is_blank_text(value: str | None) -> bool:
    return value is None or not value.strip()


def _env_bool(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    if v is None:
        return default
    return v.strip().lower() in {"1", "true", "yes", "on"}


def _has_tool_activity(messages: list[BaseMessage]) -> bool:
    for msg in messages:
        # ToolMessage from langchain_core may not always be imported/available by type here.
        if msg.__class__.__name__ == "ToolMessage":
            return True
        if isinstance(msg, AIMessage) and getattr(msg, "tool_calls", None):
            return True
    return False


DEFAULT_SYSTEM = (
    "You are an assistant for Dremio lakehouse users. "
    "Use the available MCP tools to inspect catalog metadata or run SQL when needed. "
    "Explain briefly what you did and prefer concise answers."
)


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1)
    model: str | None = Field(
        default=None,
        description="Ollama model id (defaults to OLLAMA_MODEL env, e.g. qwen2.5:3b).",
    )
    recursion_limit: int = Field(default=25, ge=3, le=100)


class ChatResponse(BaseModel):
    answer: str
    model: str
    mcp_proxy_url: str


def _sse_read_seconds_effective(raw: int) -> int:
    """MCP streamable HTTP uses SSE idle read; common misconfig is 600s (still too short for get_tools)."""
    if raw <= 660:
        return 7200
    return raw


def _build_mcp_client(auth_header: str) -> MultiServerMCPClient:
    mcp_url = _env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp/",
    )
    timeout_s = int(_env("AICHAT_MCP_TIMEOUT_SECONDS", "120"))
    sse_raw = int(_env("AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS", "7200"))
    sse_read_s = _sse_read_seconds_effective(sse_raw)
    if sse_read_s != sse_raw:
        logging.getLogger("aichat-gateway").warning(
            "AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS=%s is below MCP idle threshold; using %ss for streamable_http.",
            sse_raw,
            sse_read_s,
        )

    mcp_base_headers = {
        "Authorization": auth_header,
        "Accept": "application/json, text/event-stream",
    }
    mcp_timeout = httpx.Timeout(timeout_s, read=sse_read_s)
    def _mcp_http_client_factory(
        headers=None, timeout=None, auth=None
    ) -> httpx.AsyncClient:
        merged = {**mcp_base_headers, **(headers or {})}
        return httpx.AsyncClient(
            headers=merged,
            timeout=timeout or mcp_timeout,
            auth=auth,
            follow_redirects=True,
            # streamable-http uses a long-lived GET (SSE) plus subsequent POSTs.
            # Disabling keepalive reuse avoids head-of-line blocking on a held SSE socket.
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=0),
        )

    # langchain_mcp_adapters defaults streamable_http sse_read_timeout to 5 minutes (300s)
    # independently of httpx; must pass these or long-lived SSE stalls fail get_tools().
    return MultiServerMCPClient(
        {
            "dremio": {
                "url": mcp_url,
                "transport": "streamable_http",
                "timeout": timedelta(seconds=timeout_s),
                "sse_read_timeout": timedelta(seconds=sse_read_s),
                "httpx_client_factory": _mcp_http_client_factory,
            }
        },
        tool_name_prefix=False,
    )


app = FastAPI(title="Dremio OSS AI gateway", version="1.0.0")

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger("aichat-gateway")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "aichat-langchain-gateway"}


@app.post("/gateway/chat", response_model=ChatResponse)
async def gateway_chat(http_request: Request, body: ChatRequest) -> ChatResponse:
    auth = _normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(
            status_code=401,
            detail="Missing Authorization header (same Bearer token as Dremio / aichat plugin).",
        )

    model_name = body.model or _env("OLLAMA_MODEL", "gemma4:e4b")
    ollama_base = _env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    strict_tool_use = _env_bool("GATEWAY_STRICT_TOOL_USE", True)

    mcp_url = _env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp/",
    )
    timeout_s = int(_env("AICHAT_MCP_TIMEOUT_SECONDS", "120"))
    sse_raw = int(_env("AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS", "7200"))
    sse_read_s = _sse_read_seconds_effective(sse_raw)

    tools = None
    get_tools_deadline_s = int(_env("AICHAT_MCP_GET_TOOLS_DEADLINE_SECONDS", "45"))
    get_tools_retries = int(_env("AICHAT_MCP_GET_TOOLS_RETRIES", "1"))
    try:
        logger.info(f"Attempting MCP connection to {mcp_url}")
        last_exc: BaseException | None = None
        for attempt in range(get_tools_retries + 1):
            mcp_client = _build_mcp_client(auth)
            try:
                tools = await asyncio.wait_for(
                    mcp_client.get_tools(server_name="dremio"),
                    timeout=get_tools_deadline_s,
                )
                break
            except Exception as exc:  # includes TimeoutError and adapter errors
                last_exc = exc
                if attempt >= get_tools_retries:
                    raise

        if tools is None and last_exc is not None:
            raise last_exc
        logger.info(f"Successfully loaded {len(tools)} tools from MCP proxy.")
    except BaseException as e:
        logger.error(f"Failed to load MCP tools from proxy {mcp_url}: {e!s}")
        raise HTTPException(
            status_code=502,
            detail=f"Failed to load MCP tools (check Dremio token, plugin, dremio-mcp): {e!s}",
        ) from e

    if tools is None:
        raise HTTPException(
            status_code=502,
            detail="Failed to load MCP tools: tools were not assigned.",
        )

    llm = ChatOllama(
        model=model_name,
        base_url=ollama_base,
        temperature=0,
    )
    system_prompt = _env("GATEWAY_SYSTEM_PROMPT", DEFAULT_SYSTEM)
    agent = create_react_agent(llm, tools, prompt=system_prompt)

    try:
        out: dict[str, Any] = await agent.ainvoke(
            {"messages": [HumanMessage(content=body.message.strip())]},
            config={"recursion_limit": body.recursion_limit},
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Agent failed (Ollama running? Model pulled?): {e!s}",
        ) from e

    msgs = out.get("messages") or []
    if not msgs:
        raise HTTPException(status_code=500, detail="Agent returned no messages.")
    if strict_tool_use and not _has_tool_activity(msgs):
        raise HTTPException(
            status_code=422,
            detail=(
                "Strict tool-use is enabled but the agent did not call any MCP tool. "
                "Retry with a stronger tool-calling model or disable GATEWAY_STRICT_TOOL_USE."
            ),
        )
    last = msgs[-1]
    if not isinstance(last, AIMessage):
        last = next((m for m in reversed(msgs) if isinstance(m, AIMessage)), None)
        if last is None:
            raise HTTPException(status_code=500, detail="Agent did not produce an AI message.")

    answer_text = _message_text(last)
    if _is_blank_text(answer_text) and not strict_tool_use:
        retry_text = ""
        try:
            retry_msg = await llm.ainvoke(
                [
                    ("system", system_prompt),
                    ("human", body.message.strip()),
                ]
            )
            retry_text = _message_text(retry_msg)
        except Exception:
            pass
        if not _is_blank_text(retry_text):
            answer_text = retry_text
        else:
            fallback = "Model returned empty content. Try a different model or disable tool-use path for this request."
            answer_text = fallback

    return ChatResponse(
        answer=answer_text,
        model=model_name,
        mcp_proxy_url=mcp_url,
    )


def main() -> None:
    import uvicorn

    host = _env("GATEWAY_HOST", "127.0.0.1")
    port = int(_env("GATEWAY_PORT", "9292"))
    uvicorn.run(
        "gateway_app:app",
        host=host,
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
