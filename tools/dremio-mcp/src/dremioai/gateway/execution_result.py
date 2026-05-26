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

"""Normalize MCP RunSqlQuery payloads for the DAC UI table renderer."""

from __future__ import annotations

import json
from typing import Any


def _parse_json_maybe(raw: str) -> Any:
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return None


def _rows_from_parsed(parsed: Any) -> list[dict[str, Any]] | None:
    if isinstance(parsed, list):
        if not parsed:
            return []
        if all(isinstance(row, dict) for row in parsed):
            return parsed
        return None
    if isinstance(parsed, dict):
        if parsed.get("error"):
            return None
        result = parsed.get("result")
        if isinstance(result, list) and all(isinstance(row, dict) for row in result):
            return result
        rows = parsed.get("rows")
        if isinstance(rows, list) and all(isinstance(row, dict) for row in rows):
            return rows
    return None


def normalize_execution_result(raw: Any) -> list[dict[str, Any]] | None:
    """Return row objects for ``ChatResponse.execution_result``, or None if not tabular."""
    if raw is None:
        return None
    parsed: Any = raw
    if isinstance(raw, str):
        stripped = raw.strip()
        if not stripped:
            return None
        parsed = _parse_json_maybe(stripped)
        if parsed is None:
            return None
    rows = _rows_from_parsed(parsed)
    if rows is not None:
        return rows
    return None
