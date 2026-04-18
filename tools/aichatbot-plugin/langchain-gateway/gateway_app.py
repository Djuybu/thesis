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

import os
from datetime import timedelta
from typing import Any

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


def _build_mcp_client(auth_header: str) -> MultiServerMCPClient:
    mcp_url = _env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp",
    )
    timeout_s = int(_env("AICHAT_MCP_TIMEOUT_SECONDS", "120"))
    sse_read_s = int(_env("AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS", "600"))
    return MultiServerMCPClient(
        {
            "dremio": {
                "url": mcp_url,
                "transport": "streamable_http",
                "headers": {"Authorization": auth_header},
                "timeout": timedelta(seconds=timeout_s),
                "sse_read_timeout": timedelta(seconds=sse_read_s),
            }
        },
        tool_name_prefix=False,
    )


app = FastAPI(title="Dremio OSS AI gateway", version="1.0.0")
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

    model_name = body.model or _env("OLLAMA_MODEL", "qwen2.5:3b")
    ollama_base = _env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")

    mcp_url = _env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp",
    )

    try:
        mcp_client = _build_mcp_client(auth)
        tools = await mcp_client.get_tools(server_name="dremio")
    except Exception as e:
        raise HTTPException(
            status_code=502,
            detail=f"Failed to load MCP tools (check Dremio token, plugin, dremio-mcp): {e!s}",
        ) from e

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
    last = msgs[-1]
    if not isinstance(last, AIMessage):
        last = next((m for m in reversed(msgs) if isinstance(m, AIMessage)), None)
        if last is None:
            raise HTTPException(status_code=500, detail="Agent did not produce an AI message.")

    return ChatResponse(
        answer=_message_text(last),
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
