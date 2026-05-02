# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""ReAct agents: unified multi-tool, optional routed multi-agent mode."""

from __future__ import annotations

import re
from typing import Any, Literal

from langchain_core.messages import AIMessage, HumanMessage
from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent

from lc_config import env


def build_llm(model_name: str, ollama_base: str, temperature: float | None = None) -> ChatOllama:
    t = temperature if temperature is not None else float(env("GATEWAY_TEMPERATURE", "0"))
    return ChatOllama(model=model_name, base_url=ollama_base, temperature=t)


def compose_system_prompt(base: str, user_context: str | None) -> str:
    base = base.strip()
    if user_context and user_context.strip():
        return (
            base
            + "\n\nUser/context (from client — may include profile or task hints):\n"
            + user_context.strip()
        )
    return base


Intent = Literal["rag", "dremio", "both"]


async def classify_intent(
    llm: ChatOllama,
    ollama_base: str,
    user_message: str,
    has_rag_index: bool,
) -> Intent:
    if not has_rag_index:
        return "dremio"
    router_model = env("GATEWAY_ROUTER_MODEL", "").strip() or None
    router = (
        ChatOllama(model=router_model, base_url=ollama_base, temperature=0)
        if router_model
        else llm
    )
    prompt = (
        "Classify the user message for routing.\n"
        "Reply with exactly one word:\n"
        "- RAG — only about uploaded PDF/documents, content in files the user indexed.\n"
        "- DREMIO — Dremio/SQL/lakehouse/catalog/data warehouse queries.\n"
        "- BOTH — clearly needs both document text and lakehouse/SQL.\n\n"
        f"Message: {user_message}\n\n"
        "One word:"
    )
    out: AIMessage = await router.ainvoke([HumanMessage(content=prompt)])
    text = (out.content or "").strip().upper()
    if "BOTH" in text or text.startswith("B"):
        return "both"
    if "RAG" in text or text.startswith("R"):
        return "rag"
    if "DREMIO" in text or "SQL" in text or text.startswith("D"):
        return "dremio"
    m = re.search(r"\b(RAG|DREMIO|BOTH)\b", text)
    if m:
        w = m.group(1)
        if w == "BOTH":
            return "both"
        if w == "RAG":
            return "rag"
        return "dremio"
    return "dremio"


def pick_tools_for_intent(
    intent: Intent,
    mcp_tools: list[Any],
    rag_tool: Any | None,
    http_tools: list[Any],
) -> list[Any]:
    tools: list[Any] = []
    if intent == "rag":
        if rag_tool is not None:
            tools.append(rag_tool)
        tools.extend(http_tools)
        return tools if tools else (list(mcp_tools) + list(http_tools))
    if intent == "dremio":
        tools = list(mcp_tools)
        tools.extend(http_tools)
        return tools
    tools = list(mcp_tools)
    if rag_tool is not None:
        tools.append(rag_tool)
    tools.extend(http_tools)
    return tools


def multi_agent_enabled(http_flag: bool | None) -> bool:
    if http_flag is not None:
        return http_flag
    return env("GATEWAY_MULTI_AGENT", "").strip().lower() in ("1", "true", "yes")
