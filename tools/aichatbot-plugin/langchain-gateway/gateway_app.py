# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""HTTP gateway: LangGraph ReAct agent + Ollama + Dremio MCP + optional RAG (PDF) + HTTP tools + routed multi-agent."""

from __future__ import annotations

import os
import tempfile
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Any, Literal

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_mcp_adapters.client import MultiServerMCPClient
from langgraph.prebuilt import create_react_agent
from langgraph.types import Command
from pydantic import BaseModel, Field

from lc_agents import (
    build_llm,
    classify_intent,
    compose_system_prompt,
    multi_agent_enabled,
    pick_tools_for_intent,
)
from lc_config import env, normalize_auth_header
from lc_grounding import (
    append_data_query_workflow,
    append_strict_grounding,
    data_query_workflow_env_default,
    strict_grounding_env_default,
)
from lc_history import get_history_store, resolve_tenant
from lc_hitl_sql import build_sql_hitl_graph, interrupts_to_serializable
from lc_http_tools import build_http_get_tools
from lc_rag import default_rag_manager


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
    "Use the available MCP tools to inspect catalog metadata or run SQL when needed; "
    "discover table and column names from tools before writing SQL—do not invent identifiers. "
    "If search_uploaded_documents is available, use it for questions about PDFs the user uploaded. "
    "Explain briefly what you did and prefer concise answers."
)


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1)
    session_id: str | None = Field(
        default=None,
        description="Stable chat session id (or header X-Chat-Session-Id).",
    )
    user_id: str | None = Field(
        default=None,
        description="Logical user id for memory + RAG isolation (or header X-User-Id).",
    )
    user_context: str | None = Field(
        default=None,
        description="Extra context (e.g. Dremio profile JSON or notes) appended to the system prompt.",
    )
    model: str | None = Field(
        default=None,
        description="Ollama model id (defaults to OLLAMA_MODEL env).",
    )
    recursion_limit: int = Field(default=25, ge=3, le=100)
    multi_agent: bool | None = Field(
        default=None,
        description="If true, supervisor routes between RAG-focused vs Dremio-focused tool sets. "
        "Default follows GATEWAY_MULTI_AGENT env.",
    )
    temperature: float | None = Field(
        default=None,
        ge=0,
        le=2,
        description="Optional sampling temperature for the chat model.",
    )
    strict_grounding: bool | None = Field(
        default=None,
        description="If true, append strict anti-hallucination rules to the system prompt and default "
        "temperature to 0 when temperature is omitted. If null, use GATEWAY_STRICT_GROUNDING env (default on).",
    )
    data_query_workflow: bool | None = Field(
        default=None,
        description="If true, append discover → schema → confirm → SQL workflow for Dremio data questions. "
        "If null, use GATEWAY_DATA_QUERY_WORKFLOW env (default on).",
    )


class HitlSqlStartRequest(BaseModel):
    message: str = Field(..., min_length=1)
    session_id: str | None = None
    user_id: str | None = None
    user_context: str | None = None
    model: str | None = None
    thread_id: str | None = Field(
        default=None,
        description="Stable id for resume; generated if omitted.",
    )


class HitlSqlStartResponse(BaseModel):
    status: Literal["interrupted", "completed", "error"]
    thread_id: str
    model: str
    interrupt: list[dict[str, Any]] | None = None
    answer: str | None = None
    execution_result: Any | None = None
    error: str | None = None


class HitlSqlResumeRequest(BaseModel):
    thread_id: str = Field(..., min_length=1)
    approved: bool
    sql_override: str | None = Field(
        default=None,
        description="If set and approved, run this SQL instead of the proposed statement.",
    )


class HitlSqlResumeResponse(BaseModel):
    status: Literal["interrupted", "completed", "error"]
    thread_id: str
    model: str
    interrupt: list[dict[str, Any]] | None = None
    answer: str | None = None
    execution_result: Any | None = None
    error: str | None = None


class ChatResponse(BaseModel):
    answer: str
    model: str
    mcp_proxy_url: str
    session_id: str
    history_backend: str
    user_id: str | None = None
    rag_tenant_id: str = ""
    intent: str | None = None
    multi_agent: bool = False
    strict_grounding: bool = False
    data_query_workflow: bool = False


def _build_mcp_client(auth_header: str) -> MultiServerMCPClient:
    mcp_url = env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp",
    )
    timeout_s = int(env("AICHAT_MCP_TIMEOUT_SECONDS", "120"))
    sse_read_s = int(env("AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS", "600"))
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


app = FastAPI(title="Dremio OSS AI gateway (LangChain)", version="2.0.0")
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


def _hitl_resume_payload(approved: bool, sql_override: str | None) -> Any:
    if approved and sql_override and sql_override.strip():
        return {"approved": True, "sql": sql_override.strip()}
    if approved:
        return {"approved": True}
    return {"approved": False}


def _hitl_result_to_start_response(
    result: dict[str, Any],
    thread_id: str,
    model_name: str,
) -> HitlSqlStartResponse:
    if result.get("__interrupt__"):
        return HitlSqlStartResponse(
            status="interrupted",
            thread_id=thread_id,
            model=model_name,
            interrupt=interrupts_to_serializable(result),
        )
    err = result.get("error")
    ans = result.get("assistant_answer")
    if err and not ans:
        return HitlSqlStartResponse(
            status="error",
            thread_id=thread_id,
            model=model_name,
            error=str(err),
        )
    return HitlSqlStartResponse(
        status="completed",
        thread_id=thread_id,
        model=model_name,
        answer=ans,
        execution_result=result.get("execution_raw"),
        error=str(err) if err else None,
    )


def _hitl_result_to_resume_response(
    result: dict[str, Any],
    thread_id: str,
    model_name: str,
) -> HitlSqlResumeResponse:
    if result.get("__interrupt__"):
        return HitlSqlResumeResponse(
            status="interrupted",
            thread_id=thread_id,
            model=model_name,
            interrupt=interrupts_to_serializable(result),
        )
    err = result.get("error")
    ans = result.get("assistant_answer")
    if err and not ans:
        return HitlSqlResumeResponse(
            status="error",
            thread_id=thread_id,
            model=model_name,
            error=str(err),
        )
    return HitlSqlResumeResponse(
        status="completed",
        thread_id=thread_id,
        model=model_name,
        answer=ans,
        execution_result=result.get("execution_raw"),
        error=str(err) if err else None,
    )


@app.post("/gateway/hitl/sql/start", response_model=HitlSqlStartResponse)
async def hitl_sql_start(http_request: Request, body: HitlSqlStartRequest) -> HitlSqlStartResponse:
    """StateGraph: discover → schema → propose SQL → interrupt for human approval → (resume) execute."""
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(
            status_code=401,
            detail="Missing Authorization header (same Bearer token as Dremio / aichat plugin).",
        )
    if not env("GATEWAY_HITL_SQL_ENABLED", "true").strip().lower() in ("1", "true", "yes", "on"):
        raise HTTPException(status_code=404, detail="HITL SQL workflow is disabled (GATEWAY_HITL_SQL_ENABLED).")

    model_name = body.model or env("OLLAMA_MODEL", "qwen2.5:3b")
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    try:
        mcp_client = _build_mcp_client(auth)
        mcp_tools = await mcp_client.get_tools(server_name="dremio")
    except Exception as e:
        raise HTTPException(
            status_code=502,
            detail=f"Failed to load MCP tools: {e!s}",
        ) from e

    llm = build_llm(model_name, ollama_base, temperature=0.0)
    graph = build_sql_hitl_graph(llm, mcp_tools)
    thread_id = body.thread_id.strip() if body.thread_id and body.thread_id.strip() else f"hitl-sql-{uuid.uuid4()}"
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}, "recursion_limit": 80}
    initial: dict[str, Any] = {
        "user_question": body.message.strip(),
        "user_context": body.user_context,
    }
    try:
        result = await graph.ainvoke(initial, config=config)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"HITL graph failed: {e!s}") from e
    return _hitl_result_to_start_response(result, thread_id, model_name)


@app.post("/gateway/hitl/sql/resume", response_model=HitlSqlResumeResponse)
async def hitl_sql_resume(http_request: Request, body: HitlSqlResumeRequest) -> HitlSqlResumeResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")
    if not env("GATEWAY_HITL_SQL_ENABLED", "true").strip().lower() in ("1", "true", "yes", "on"):
        raise HTTPException(status_code=404, detail="HITL SQL workflow is disabled (GATEWAY_HITL_SQL_ENABLED).")

    model_name = env("OLLAMA_MODEL", "qwen2.5:3b")
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    try:
        mcp_client = _build_mcp_client(auth)
        mcp_tools = await mcp_client.get_tools(server_name="dremio")
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to load MCP tools: {e!s}") from e

    llm = build_llm(model_name, ollama_base, temperature=0.0)
    graph = build_sql_hitl_graph(llm, mcp_tools)
    thread_id = body.thread_id.strip()
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}, "recursion_limit": 80}
    resume_val = _hitl_resume_payload(body.approved, body.sql_override)
    try:
        result = await graph.ainvoke(Command(resume=resume_val), config=config)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"HITL resume failed: {e!s}") from e
    return _hitl_result_to_resume_response(result, thread_id, model_name)


@app.post("/gateway/chat", response_model=ChatResponse)
async def gateway_chat(http_request: Request, body: ChatRequest) -> ChatResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(
            status_code=401,
            detail="Missing Authorization header (same Bearer token as Dremio / aichat plugin).",
        )

    model_name = body.model or env("OLLAMA_MODEL", "qwen2.5:3b")
    tenant = resolve_tenant(http_request, body.session_id, body.user_id)
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    mcp_url = env(
        "AICHAT_MCP_PROXY_URL",
        "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp",
    )

    try:
        mcp_client = _build_mcp_client(auth)
        mcp_tools = await mcp_client.get_tools(server_name="dremio")
    except Exception as e:
        raise HTTPException(
            status_code=502,
            detail=f"Failed to load MCP tools (check Dremio token, plugin, dremio-mcp): {e!s}",
        ) from e

    rag_mgr = default_rag_manager(ollama_base)
    has_rag = bool(rag_mgr and rag_mgr.has_index(tenant.rag_tenant_id))
    rag_tool = rag_mgr.make_search_tool(tenant.rag_tenant_id) if rag_mgr else None
    http_tools = build_http_get_tools()

    use_multi = multi_agent_enabled(body.multi_agent)
    intent_label: str | None = None
    strict = (
        body.strict_grounding
        if body.strict_grounding is not None
        else strict_grounding_env_default()
    )
    data_wf = (
        body.data_query_workflow
        if body.data_query_workflow is not None
        else data_query_workflow_env_default()
    )
    eff_temperature = body.temperature
    if strict and eff_temperature is None:
        eff_temperature = 0.0
    llm = build_llm(model_name, ollama_base, temperature=eff_temperature)

    if use_multi and rag_mgr and has_rag and rag_tool is not None:
        intent = await classify_intent(llm, ollama_base, body.message.strip(), True)
        intent_label = intent
        tools = pick_tools_for_intent(intent, mcp_tools, rag_tool, http_tools)
    else:
        tools = list(mcp_tools)
        if rag_tool is not None and has_rag:
            tools.append(rag_tool)
        tools.extend(http_tools)

    system_prompt = append_data_query_workflow(
        append_strict_grounding(
            compose_system_prompt(
                env("GATEWAY_SYSTEM_PROMPT", DEFAULT_SYSTEM),
                body.user_context,
            ),
            strict,
        ),
        data_wf,
    )
    agent = create_react_agent(llm, tools, prompt=system_prompt)
    agent_with_history = RunnableWithMessageHistory(
        agent,
        lambda sid: get_history_store(sid)[0],
        input_messages_key="messages",
        history_messages_key="messages",
    )
    _, history_backend = get_history_store(tenant.history_key)

    try:
        out: dict[str, Any] = await agent_with_history.ainvoke(
            {"messages": [HumanMessage(content=body.message.strip())]},
            config={
                "recursion_limit": body.recursion_limit,
                "configurable": {"session_id": tenant.history_key},
            },
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
        session_id=tenant.session_id,
        history_backend=history_backend,
        user_id=tenant.user_id,
        rag_tenant_id=tenant.rag_tenant_id,
        intent=intent_label,
        multi_agent=use_multi and bool(rag_mgr and has_rag),
        strict_grounding=strict,
        data_query_workflow=data_wf,
    )


class RagStatusResponse(BaseModel):
    rag_enabled: bool
    rag_tenant_id: str
    has_index: bool
    chunk_count: int


@app.get("/gateway/rag/status", response_model=RagStatusResponse)
async def rag_status(
    http_request: Request,
    session_id: str | None = None,
    user_id: str | None = None,
) -> RagStatusResponse:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    rag_mgr = default_rag_manager(ollama_base)
    tenant = resolve_tenant(http_request, session_id, user_id)
    if not rag_mgr:
        return RagStatusResponse(
            rag_enabled=False,
            rag_tenant_id=tenant.rag_tenant_id,
            has_index=False,
            chunk_count=0,
        )
    chunks = rag_mgr.total_chunks(tenant.rag_tenant_id)
    return RagStatusResponse(
        rag_enabled=True,
        rag_tenant_id=tenant.rag_tenant_id,
        has_index=rag_mgr.has_index(tenant.rag_tenant_id),
        chunk_count=chunks,
    )


@app.post("/gateway/rag/upload")
async def rag_upload(
    http_request: Request,
    file: UploadFile = File(...),
    session_id: str | None = Form(None),
    user_id: str | None = Form(None),
) -> dict[str, Any]:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")
    if not file.filename or not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="Upload a .pdf file.")
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    rag_mgr = default_rag_manager(ollama_base)
    if not rag_mgr:
        raise HTTPException(
            status_code=503,
            detail="RAG is disabled. Set GATEWAY_RAG_DIR and install embedding model (e.g. ollama pull nomic-embed-text).",
        )
    tenant = resolve_tenant(http_request, session_id, user_id)
    suffix = ".pdf"
    fd, path = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    try:
        data = await file.read()
        with open(path, "wb") as f:
            f.write(data)
        n = rag_mgr.ingest_pdf_path(tenant.rag_tenant_id, Path(path))
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return {
        "ok": True,
        "filename": file.filename,
        "chunks_added": n,
        "rag_tenant_id": tenant.rag_tenant_id,
        "session_id": tenant.session_id,
        "user_id": tenant.user_id,
    }


@app.delete("/gateway/rag/clear")
async def rag_clear(
    http_request: Request,
    session_id: str | None = None,
    user_id: str | None = None,
) -> dict[str, Any]:
    auth = normalize_auth_header(http_request.headers.get("Authorization"))
    if not auth:
        raise HTTPException(status_code=401, detail="Missing Authorization header.")
    ollama_base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    rag_mgr = default_rag_manager(ollama_base)
    if not rag_mgr:
        raise HTTPException(status_code=503, detail="RAG is disabled (GATEWAY_RAG_DIR not set).")
    tenant = resolve_tenant(http_request, session_id, user_id)
    rag_mgr.clear(tenant.rag_tenant_id)
    return {"ok": True, "rag_tenant_id": tenant.rag_tenant_id}


def main() -> None:
    import uvicorn

    host = env("GATEWAY_HOST", "127.0.0.1")
    port = int(env("GATEWAY_PORT", "9292"))
    uvicorn.run(
        "gateway_app:app",
        host=host,
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
