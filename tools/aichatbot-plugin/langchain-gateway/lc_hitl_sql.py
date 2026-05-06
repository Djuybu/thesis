# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""LangGraph StateGraph + interrupt() for SQL approval before RunSqlQuery (human-in-the-loop)."""

from __future__ import annotations

import json
import re
from typing import Any, Literal, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_ollama import ChatOllama
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt
from pydantic import BaseModel, Field

# Shared checkpointer so /start and /resume share state by thread_id (per gateway process).
_hitl_checkpointer: InMemorySaver | None = None


def get_hitl_checkpointer() -> InMemorySaver:
    global _hitl_checkpointer
    if _hitl_checkpointer is None:
        _hitl_checkpointer = InMemorySaver()
    return _hitl_checkpointer


class SqlHitlState(TypedDict, total=False):
    user_question: str
    user_context: str | None
    discover_result: str
    table_fqn: str
    schema_text: str
    proposed_sql: str
    sql_rationale: str
    approval: bool
    final_sql: str
    execution_raw: Any
    assistant_answer: str
    error: str | None


class TablePick(BaseModel):
    """Best Dremio table or view to answer the question."""

    table_fqn: str = Field(
        ...,
        description="Single fully-qualified Dremio identifier, e.g. \"source\".\"folder\".\"table\"",
    )
    rationale: str = Field(default="", description="Why this table matches the user question")


class SqlProposal(BaseModel):
    proposed_sql: str = Field(..., description="A single SELECT query for Dremio")
    rationale: str = Field(default="", description="Brief note on filters and columns")


def _tool_by_name(tools: list[Any], *candidates: str) -> Any | None:
    by = {t.name: t for t in tools}
    for c in candidates:
        if c in by:
            return by[c]
    lower = {k.lower(): v for k, v in by.items()}
    for c in candidates:
        if c.lower() in lower:
            return lower[c.lower()]
    return None


def _json_compact(obj: Any, max_len: int = 12000) -> str:
    try:
        s = json.dumps(obj, default=str, ensure_ascii=False, indent=2)
    except TypeError:
        s = str(obj)
    if len(s) > max_len:
        return s[:max_len] + "\n…(truncated)"
    return s


def build_sql_hitl_graph(llm: ChatOllama, mcp_tools: list[Any]):
    search_tool = _tool_by_name(
        mcp_tools,
        "SearchTableAndViews",
        "search_table_and_views",
    )
    schema_tool = _tool_by_name(
        mcp_tools,
        "GetSchemaOfTable",
        "get_schema_of_table",
    )
    sql_tool = _tool_by_name(
        mcp_tools,
        "RunSqlQuery",
        "run_sql_query",
    )

    structured_pick = llm.with_structured_output(TablePick)
    structured_sql = llm.with_structured_output(SqlProposal)

    async def node_discover(state: SqlHitlState) -> dict[str, Any]:
        if not search_tool:
            return {
                "discover_result": "",
                "error": "MCP tool SearchTableAndViews not available.",
            }
        try:
            out = await search_tool.ainvoke({"query": state["user_question"]})
            return {"discover_result": _json_compact(out)}
        except Exception as e:
            return {"discover_result": "", "error": f"Discover failed: {e!s}"}

    async def node_pick_table(state: SqlHitlState) -> dict[str, Any]:
        if state.get("error"):
            return {}
        ctx = (state.get("user_context") or "").strip()
        sys = (
            "You pick exactly one Dremio table or view FQN from the discovery JSON below. "
            "Use the exact name string as it appears in the data. If none fit, output the closest candidate."
        )
        if ctx:
            sys += f"\n\nUser context:\n{ctx}"
        msg = f"User question:\n{state['user_question']}\n\nDiscovery:\n{state.get('discover_result', '')}"
        try:
            pick: TablePick = await structured_pick.ainvoke(
                [SystemMessage(content=sys), HumanMessage(content=msg)]
            )
            return {"table_fqn": pick.table_fqn.strip()}
        except Exception as e:
            return {"error": f"Table pick failed: {e!s}"}

    async def node_fetch_schema(state: SqlHitlState) -> dict[str, Any]:
        if state.get("error") or not state.get("table_fqn"):
            return {}
        if not schema_tool:
            return {"schema_text": "(schema tool unavailable)", "error": state.get("error")}
        try:
            out = await schema_tool.ainvoke({"table_name": state["table_fqn"]})
            return {"schema_text": _json_compact(out, max_len=16000)}
        except Exception as e:
            return {"schema_text": "", "error": f"Schema failed: {e!s}"}

    async def node_propose_sql(state: SqlHitlState) -> dict[str, Any]:
        if state.get("error"):
            return {}
        sys = (
            "Write one Dremio SQL SELECT for the user question. "
            "Use only tables/columns from the schema JSON. Prefer LIMIT 100 for exploration. "
            "No DML. Double-quote identifiers if they are reserved or mixed case."
        )
        msg = (
            f"Question:\n{state['user_question']}\n\n"
            f"Table:\n{state.get('table_fqn', '')}\n\n"
            f"Schema:\n{state.get('schema_text', '')}"
        )
        try:
            prop: SqlProposal = await structured_sql.ainvoke(
                [SystemMessage(content=sys), HumanMessage(content=msg)]
            )
            sql = prop.proposed_sql.strip()
            if not re.match(r"^\s*select\b", sql, re.IGNORECASE) and not re.match(
                r"^\s*with\b", sql, re.IGNORECASE
            ):
                sql = f"SELECT * FROM {state.get('table_fqn', 'unknown')} LIMIT 10"
            return {"proposed_sql": sql, "sql_rationale": prop.rationale}
        except Exception as e:
            return {"error": f"SQL proposal failed: {e!s}"}

    def node_human_gate(state: SqlHitlState) -> dict[str, Any]:
        if state.get("error"):
            return {
                "approval": False,
                "final_sql": "",
                "assistant_answer": state.get("error", "Error before approval step."),
            }
        payload = {
            "action": "sql_approval",
            "message": (
                "Review the proposed SQL before execution on Dremio. "
                "Approve in the next request or reject."
            ),
            "table_fqn": state.get("table_fqn"),
            "proposed_sql": state.get("proposed_sql"),
            "rationale": state.get("sql_rationale"),
            "discover_excerpt": (state.get("discover_result") or "")[:4000],
        }
        raw: Any = interrupt(payload)
        approved = False
        override: str | None = None
        if isinstance(raw, dict):
            approved = bool(raw.get("approved"))
            override = raw.get("sql") or raw.get("edited_sql") or raw.get("sql_override")
        elif isinstance(raw, bool):
            approved = raw
        final_sql = (override or state.get("proposed_sql") or "").strip()
        return {"approval": approved, "final_sql": final_sql}

    def route_after_gate(state: SqlHitlState) -> Literal["execute_sql", "reject_end"]:
        if state.get("approval") and state.get("final_sql"):
            return "execute_sql"
        return "reject_end"

    async def node_execute_sql(state: SqlHitlState) -> dict[str, Any]:
        if not sql_tool:
            return {"execution_raw": {"error": "RunSqlQuery tool unavailable"}, "error": "No SQL tool"}
        try:
            out = await sql_tool.ainvoke({"query": state["final_sql"]})
            return {"execution_raw": out}
        except Exception as e:
            return {"execution_raw": {"error": str(e)}, "error": str(e)}

    def node_reject_end(state: SqlHitlState) -> dict[str, Any]:
        if state.get("assistant_answer"):
            return {}
        return {
            "assistant_answer": (
                "Đã hủy: không thực thi truy vấn SQL (bạn từ chối hoặc thiếu câu lệnh hợp lệ)."
            )
        }

    async def node_finalize(state: SqlHitlState) -> dict[str, Any]:
        if state.get("assistant_answer"):
            return {}
        raw = state.get("execution_raw")
        sys = "Summarize the query result for the user in concise language. If there is an error field, explain it."
        msg = f"User question:\n{state['user_question']}\n\nSQL:\n{state.get('final_sql')}\n\nResult:\n{_json_compact(raw, 8000)}"
        try:
            resp = await llm.ainvoke([SystemMessage(content=sys), HumanMessage(content=msg)])
            text = getattr(resp, "content", None) or str(resp)
            if isinstance(text, list):
                text = " ".join(str(x) for x in text)
            return {"assistant_answer": str(text).strip()}
        except Exception as e:
            return {"assistant_answer": f"Query ran but summarization failed: {e!s}. Raw: {_json_compact(raw, 2000)}"}

    def node_error_end(state: SqlHitlState) -> dict[str, Any]:
        err = state.get("error") or "Unknown error"
        return {"assistant_answer": f"Không thể hoàn tất luồng SQL: {err}"}

    def route_after_discover(state: SqlHitlState) -> Literal["pick_table", "error_end"]:
        return "error_end" if state.get("error") else "pick_table"

    def route_after_pick(state: SqlHitlState) -> Literal["fetch_schema", "error_end"]:
        return "error_end" if state.get("error") else "fetch_schema"

    def route_after_schema(state: SqlHitlState) -> Literal["propose_sql", "error_end"]:
        return "error_end" if state.get("error") else "propose_sql"

    def route_after_propose(state: SqlHitlState) -> Literal["human_gate", "error_end"]:
        return "error_end" if state.get("error") else "human_gate"

    g = StateGraph(SqlHitlState)
    g.add_node("discover", node_discover)
    g.add_node("pick_table", node_pick_table)
    g.add_node("fetch_schema", node_fetch_schema)
    g.add_node("propose_sql", node_propose_sql)
    g.add_node("human_gate", node_human_gate)
    g.add_node("execute_sql", node_execute_sql)
    g.add_node("reject_end", node_reject_end)
    g.add_node("finalize", node_finalize)
    g.add_node("error_end", node_error_end)

    g.add_edge(START, "discover")
    g.add_conditional_edges("discover", route_after_discover, {"pick_table": "pick_table", "error_end": "error_end"})
    g.add_conditional_edges("pick_table", route_after_pick, {"fetch_schema": "fetch_schema", "error_end": "error_end"})
    g.add_conditional_edges(
        "fetch_schema", route_after_schema, {"propose_sql": "propose_sql", "error_end": "error_end"}
    )
    g.add_conditional_edges(
        "propose_sql", route_after_propose, {"human_gate": "human_gate", "error_end": "error_end"}
    )
    g.add_conditional_edges(
        "human_gate",
        route_after_gate,
        {"execute_sql": "execute_sql", "reject_end": "reject_end"},
    )
    g.add_edge("execute_sql", "finalize")
    g.add_edge("finalize", END)
    g.add_edge("reject_end", END)
    g.add_edge("error_end", END)

    return g.compile(checkpointer=get_hitl_checkpointer())


def interrupts_to_serializable(result: dict[str, Any]) -> list[dict[str, Any]]:
    intr = result.get("__interrupt__") or []
    out: list[dict[str, Any]] = []
    for item in intr:
        val = getattr(item, "value", item)
        if isinstance(val, dict):
            out.append(val)
        else:
            out.append({"value": val})
    return out
