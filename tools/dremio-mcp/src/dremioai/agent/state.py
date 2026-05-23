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

"""AgentState for the Dremio SQL HITL graph (guardrail → discovery → metadata confirmation → SQL gen → refinement → execute)."""

from __future__ import annotations

from typing import Any, TypedDict


class AgentState(TypedDict, total=False):
    user_question: str
    user_context: str | None

    # Same Authorization header the client sent to the gateway (e.g. ``Bearer <PAT>``).
    # Used to call Dremio REST directly when MCP tool context may not carry PAT overrides.
    dremio_auth_header: str | None

    # discovery shortcut (e.g. list sources via SQL — skip table pick / HITL SQL)
    discovery_short_circuit: bool

    # guardrail
    is_safe: bool
    is_greeting: bool
    is_general: bool
    guardrail_reason: str

    # discovery
    discover_result: str
    discovered_tables: list[dict[str, Any]]

    # metadata confirmation (HITL)
    table_fqn: str
    schema_text: str
    metadata_approved: bool

    # schema-only short-circuit (user asks to *describe columns/fields* of one table)
    schema_short_circuit: bool

    # SQL generation
    proposed_sql: str
    sql_rationale: str

    # refinement (HITL)
    sql_approved: bool
    final_sql: str

    # execution
    execution_raw: Any
    assistant_answer: str

    error: str | None
