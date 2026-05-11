from __future__ import annotations

import asyncio
import re
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import interrupt

from dremioai.agent.nodes.common import (
    SqlProposal,
    _agent_trace_llm_content_on,
    _json_compact,
    _preview,
    _trace,
    _trace_verbose,
    agent_debug_ndjson,
    structured_llm_timeout_seconds,
)
from dremioai.agent.state import AgentState
from dremioai.agent.tools import get_sql_tool


def make_metadata_confirmation_node():
    """HITL pause — presents discovered metadata for user approval before SQL generation."""

    def metadata_confirmation_node(state: AgentState) -> dict[str, Any]:
        if state.get("error"):
            return {
                "metadata_approved": False,
                "assistant_answer": state.get("error", "Error before metadata confirmation."),
            }

        _trace(
            "step=metadata_confirmation HITL interrupt table=%s (resume via /aichat/v1/chat/resume)",
            state.get("table_fqn"),
        )
        # #region agent log
        agent_debug_ndjson(
            "H4",
            "sql_flow.py:metadata_confirmation_node",
            "hitl_interrupt_metadata",
            {
                "table_fqn_preview": str(state.get("table_fqn") or "")[:240],
                "schema_text_chars": len(state.get("schema_text") or ""),
            },
        )
        # #endregion
        payload = {
            "action": "metadata_confirmation",
            "message": "Review discovered tables and schema before SQL generation.",
            "table_fqn": state.get("table_fqn"),
            "schema_text": (state.get("schema_text") or "")[:4000],
            "discover_excerpt": (state.get("discover_result") or "")[:4000],
        }
        raw: Any = interrupt(payload)

        approved = False
        if isinstance(raw, dict):
            approved = bool(raw.get("approved", raw.get("approve", False)))
        elif isinstance(raw, bool):
            approved = raw

        _trace("step=metadata_confirmation resume approved=%s", approved)
        return {"metadata_approved": approved}

    return metadata_confirmation_node


def make_sql_gen_node(llm: Any):
    """Generates a read-only SQL query from validated schema + user question."""
    structured_sql = llm.with_structured_output(SqlProposal)

    async def sql_gen_node(state: AgentState) -> dict[str, Any]:
        if state.get("error"):
            return {}
        _trace("step=sql_gen LLM structured SqlProposal")
        sys = (
            "/no_think\n"
            "Write one Dremio SQL SELECT for the user question. "
            "Use only tables/columns from the schema JSON. Prefer LIMIT 100 for exploration. "
            "No DML. Double-quote identifiers if they are reserved or mixed case."
        )
        msg = (
            f"Question:\n{state['user_question']}\n\n"
            f"Table:\n{state.get('table_fqn', '')}\n\n"
            f"Schema:\n{state.get('schema_text', '')}"
        )
        _trace_verbose(
            "step=sql_gen prompt_chars sys=%s user=%s schema=%s",
            len(sys),
            len(state.get("user_question") or ""),
            len(state.get("schema_text") or ""),
        )
        if _agent_trace_llm_content_on():
            _trace_verbose("step=sql_gen prompt_sys=%s", _preview(sys, 1000))
            _trace_verbose("step=sql_gen prompt_user=%s", _preview(msg, 1500))
        try:
            _msgs = [SystemMessage(content=sys), HumanMessage(content=msg)]
            _to = structured_llm_timeout_seconds()
            if _to is not None:
                prop = await asyncio.wait_for(
                    structured_sql.ainvoke(_msgs),
                    timeout=_to,
                )
            else:
                prop = await structured_sql.ainvoke(_msgs)
            sql = prop.proposed_sql.strip()
            if not re.match(r"^\s*select\b", sql, re.IGNORECASE) and not re.match(
                r"^\s*with\b", sql, re.IGNORECASE
            ):
                sql = f"SELECT * FROM {state.get('table_fqn', 'unknown')} LIMIT 10"
            _trace(
                "step=sql_gen done sql=%s rationale=%s",
                _preview(sql, 400),
                _preview(prop.rationale, 160),
            )
            _trace_verbose(
                "step=sql_gen parsed_sql_proposal=%s",
                _preview(_json_compact(prop.model_dump()), 1000),
            )
            return {"proposed_sql": sql, "sql_rationale": prop.rationale}
        except Exception as e:
            _trace("step=sql_gen error %s", e)
            return {"error": f"SQL generation failed: {e!s}"}

    return sql_gen_node


def make_refinement_node():
    """HITL pause — presents the drafted SQL for user review/edit/approval."""

    def refinement_node(state: AgentState) -> dict[str, Any]:
        if state.get("error"):
            return {
                "sql_approved": False,
                "final_sql": "",
                "assistant_answer": state.get("error", "Error before SQL approval."),
            }
        _trace(
            "step=refinement HITL interrupt sql_preview=%s (resume via /aichat/v1/chat/resume)",
            _preview(state.get("proposed_sql"), 360),
        )
        payload = {
            "action": "sql_approval",
            "message": "Review the proposed SQL before execution on Dremio.",
            "table_fqn": state.get("table_fqn"),
            "proposed_sql": state.get("proposed_sql"),
            "rationale": state.get("sql_rationale"),
        }
        raw: Any = interrupt(payload)

        approved = False
        override: str | None = None
        if isinstance(raw, dict):
            approved = bool(raw.get("approved", raw.get("approve", False)))
            override = raw.get("sql") or raw.get("edited_sql") or raw.get("sql_override")
        elif isinstance(raw, bool):
            approved = raw

        final_sql = (override or state.get("proposed_sql") or "").strip()
        _trace(
            "step=refinement resume approved=%s final_sql=%s",
            approved,
            _preview(final_sql, 400),
        )
        return {"sql_approved": approved, "final_sql": final_sql}

    return refinement_node


def make_execute_node(mcp_tools: list[Any]):
    """Invokes RunSqlQuery via MCP to execute the approved query."""
    sql_tool = get_sql_tool(mcp_tools)

    async def execute_node(state: AgentState) -> dict[str, Any]:
        if not sql_tool:
            _trace("step=execute aborted RunSqlQuery missing")
            return {"execution_raw": {"error": "RunSqlQuery tool unavailable"}, "error": "No SQL tool"}
        _trace(
            "step=execute MCP tool=RunSqlQuery sql=%s",
            _preview(state.get("final_sql"), 600),
        )
        try:
            out = await sql_tool.ainvoke({"query": state["final_sql"]})
            _trace("step=execute done result_type=%s", type(out).__name__)
            _trace_verbose("step=execute result_excerpt=%s", _preview(_json_compact(out, 5000), 1200))
            return {"execution_raw": out}
        except Exception as e:
            _trace("step=execute error %s", e)
            return {"execution_raw": {"error": str(e)}, "error": str(e)}

    return execute_node


def make_finalize_node(llm: Any):
    """Summarises query results into a user-friendly response."""

    async def finalize_node(state: AgentState) -> dict[str, Any]:
        if state.get("assistant_answer"):
            return {}
        _trace("step=finalize LLM summarize results")
        raw = state.get("execution_raw")
        sys = "/no_think\nSummarize the query result for the user in concise language. If there is an error field, explain it."
        result_json = _json_compact(raw, 8000)
        msg = (
            f"User question:\n{state['user_question']}\n\n"
            f"SQL:\n{state.get('final_sql')}\n\n"
            f"Result:\n{result_json}"
        )
        _trace_verbose(
            "step=finalize prompt_chars sys=%s user=%s result=%s",
            len(sys),
            len(state.get("user_question") or ""),
            len(result_json),
        )
        if _agent_trace_llm_content_on():
            _trace_verbose("step=finalize prompt_sys=%s", _preview(sys, 600))
            _trace_verbose("step=finalize prompt_user=%s", _preview(msg, 1500))
        try:
            resp = await llm.ainvoke([SystemMessage(content=sys), HumanMessage(content=msg)])
            text = getattr(resp, "content", None) or str(resp)
            if isinstance(text, list):
                text = " ".join(str(x) for x in text)
            ans = str(text).strip()
            if _agent_trace_llm_content_on():
                _trace_verbose("step=finalize raw_response=%s", _preview(str(resp.content), 1200))
            _trace("step=finalize done answer_chars=%s", len(ans))
            return {"assistant_answer": ans}
        except Exception as e:
            _trace("step=finalize error %s", e)
            return {"assistant_answer": f"Query ran but summarization failed: {e!s}. Raw: {_json_compact(raw, 2000)}"}

    return finalize_node


def make_reject_node():
    def reject_node(state: AgentState) -> dict[str, Any]:
        if state.get("assistant_answer"):
            return {}
        _trace("step=reject_end user declined metadata or SQL")
        return {"assistant_answer": "Da huy: khong thuc thi truy van SQL (ban tu choi hoac thieu cau lenh hop le)."}

    return reject_node


def make_error_node():
    def error_node(state: AgentState) -> dict[str, Any]:
        err = state.get("error") or "Unknown error"
        _trace("step=error_end %s", _preview(str(err), 300))
        return {"assistant_answer": f"Khong the hoan tat: {err}"}

    return error_node


def make_early_end_node():
    """No-op pass-through when discovery already set ``assistant_answer`` (short-circuit)."""

    def early_end_node(state: AgentState) -> dict[str, Any]:
        _trace("step=early_end short_circuit done answer_chars=%s", len(state.get("assistant_answer") or ""))
        return {}

    return early_end_node


def route_after_metadata(state: AgentState) -> Literal["sql_gen", "reject_end"]:
    nxt: Literal["sql_gen", "reject_end"] = (
        "sql_gen" if state.get("metadata_approved") else "reject_end"
    )
    _trace("route metadata_confirmation -> %s", nxt)
    return nxt


def route_after_sql_gen(state: AgentState) -> Literal["refinement", "error_end"]:
    nxt: Literal["refinement", "error_end"] = "error_end" if state.get("error") else "refinement"
    _trace("route sql_gen -> %s", nxt)
    return nxt


def route_after_refinement(state: AgentState) -> Literal["execute", "reject_end"]:
    if state.get("sql_approved") and state.get("final_sql"):
        _trace("route refinement -> execute")
        return "execute"
    _trace("route refinement -> reject_end")
    return "reject_end"
