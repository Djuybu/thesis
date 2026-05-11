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

"""Wrappers that locate MCP tools by name from the discovered tool list."""

from __future__ import annotations

from typing import Any


def tool_by_name(tools: list[Any], *candidates: str) -> Any | None:
    by = {t.name: t for t in tools}
    for c in candidates:
        if c in by:
            return by[c]
    lower = {k.lower(): v for k, v in by.items()}
    for c in candidates:
        if c.lower() in lower:
            return lower[c.lower()]
    return None


def get_search_tool(mcp_tools: list[Any]) -> Any | None:
    return tool_by_name(mcp_tools, "SearchTableAndViews", "search_table_and_views")


def get_schema_tool(mcp_tools: list[Any]) -> Any | None:
    return tool_by_name(mcp_tools, "GetSchemaOfTable", "get_schema_of_table")


def get_sql_tool(mcp_tools: list[Any]) -> Any | None:
    t = tool_by_name(
        mcp_tools,
        "RunSqlQuery",
        "run_sql_query",
        "run-sql-query",
        "runSqlQuery",
    )
    if t:
        return t
    for tool in mcp_tools:
        n = (getattr(tool, "name", None) or "").replace("-", "_").lower()
        if n in ("runsqlquery", "run_sql_query"):
            return tool
    return None
