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

"""Compile the Dremio SQL HITL StateGraph.

Graph flow (from dremio_mcp.pdf §1):
  START → guardrail → discovery → pick_and_schema → metadata_confirmation (HITL)
        → sql_gen → refinement (HITL) → execute → finalize → END
"""

from __future__ import annotations

from typing import Any

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from dremioai.agent.nodes import (
    make_discovery_node,
    make_early_end_node,
    make_error_node,
    make_execute_node,
    make_finalize_node,
    make_general_reply_node,
    make_greetings_node,
    make_guardrail_node,
    make_guardrail_reject_node,
    make_metadata_confirmation_node,
    make_pick_and_schema_node,
    make_refinement_node,
    make_reject_node,
    make_sql_gen_node,
    route_after_discovery,
    route_after_guardrail,
    route_after_metadata,
    route_after_pick_schema,
    route_after_refinement,
    route_after_sql_gen,
)
from dremioai.agent.state import AgentState

_checkpointer: InMemorySaver | None = None


def get_checkpointer() -> InMemorySaver:
    global _checkpointer
    if _checkpointer is None:
        _checkpointer = InMemorySaver()
    return _checkpointer


def build_graph(llm: Any, mcp_tools: list[Any]) -> Any:
    """Construct and compile the StateGraph with HITL interrupt points."""

    g = StateGraph(AgentState)

    g.add_node("guardrail", make_guardrail_node(llm))
    g.add_node("greetings", make_greetings_node())
    g.add_node("general_reply", make_general_reply_node())
    g.add_node("guardrail_reject", make_guardrail_reject_node())
    g.add_node("discovery", make_discovery_node(mcp_tools))
    g.add_node("early_end", make_early_end_node())
    g.add_node("pick_and_schema", make_pick_and_schema_node(llm, mcp_tools))
    g.add_node("metadata_confirmation", make_metadata_confirmation_node())
    g.add_node("sql_gen", make_sql_gen_node(llm))
    g.add_node("refinement", make_refinement_node())
    g.add_node("execute", make_execute_node(mcp_tools))
    g.add_node("finalize", make_finalize_node(llm))
    g.add_node("reject_end", make_reject_node())
    g.add_node("error_end", make_error_node())

    g.add_edge(START, "guardrail")
    g.add_conditional_edges("guardrail", route_after_guardrail, {
        "greetings": "greetings",
        "general_reply": "general_reply",
        "discovery": "discovery",
        "guardrail_reject": "guardrail_reject",
    })
    g.add_edge("greetings", END)
    g.add_edge("general_reply", END)
    g.add_edge("guardrail_reject", END)

    g.add_conditional_edges("discovery", route_after_discovery, {
        "pick_and_schema": "pick_and_schema",
        "error_end": "error_end",
        "early_end": "early_end",
    })
    g.add_conditional_edges("pick_and_schema", route_after_pick_schema, {
        "metadata_confirmation": "metadata_confirmation",
        "early_end": "early_end",
        "error_end": "error_end",
    })
    g.add_conditional_edges("metadata_confirmation", route_after_metadata, {
        "sql_gen": "sql_gen",
        "reject_end": "reject_end",
    })
    g.add_conditional_edges("sql_gen", route_after_sql_gen, {
        "refinement": "refinement",
        "error_end": "error_end",
    })
    g.add_conditional_edges("refinement", route_after_refinement, {
        "execute": "execute",
        "reject_end": "reject_end",
    })
    g.add_edge("execute", "finalize")
    g.add_edge("finalize", END)
    g.add_edge("reject_end", END)
    g.add_edge("error_end", END)
    g.add_edge("early_end", END)

    return g.compile(checkpointer=get_checkpointer())
