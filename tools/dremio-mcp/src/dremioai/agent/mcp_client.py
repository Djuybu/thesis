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

"""MCP Client/Host initialisation — connects to Dremio MCP Server and discovers tools."""

from __future__ import annotations

import logging
import os
import time
from datetime import timedelta
from typing import Any

import httpx
from langchain_mcp_adapters.client import MultiServerMCPClient

logger = logging.getLogger("dremio-sql-agent")

# Cache tool list per auth token: {auth_header: (tools, timestamp)}
_tools_cache: dict[str, tuple[list, float]] = {}
# Env: DREMIO_MCP_TOOLS_CACHE_TTL (seconds, default 300 = 5 min). Set to 0 to disable.
_TOOLS_CACHE_TTL_DEFAULT = 300


def _flatten_exception_message(exc: BaseException) -> str:
    """Turn nested TaskGroup/ExceptionGroup failures into one actionable string."""
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(_flatten_exception_message(e) for e in exc.exceptions)
    return f"{type(exc).__name__}: {exc}"


def _env(name: str, default: str) -> str:
    v = os.environ.get(name)
    return v.strip() if v and v.strip() else default


def _sse_read_seconds(raw: int) -> int:
    if raw <= 660:
        return 7200
    return raw


def build_mcp_client(auth_header: str) -> MultiServerMCPClient:
    """Construct a MultiServerMCPClient pointing at the Dremio MCP HTTP endpoint."""
    mcp_url = _env("DREMIO_MCP_URL", "http://127.0.0.1:8080/mcp/")
    timeout_s = int(_env("DREMIO_MCP_TIMEOUT_SECONDS", "120"))
    sse_raw = int(_env("DREMIO_MCP_SSE_READ_TIMEOUT_SECONDS", "7200"))
    sse_read_s = _sse_read_seconds(sse_raw)

    mcp_base_headers = {
        "Authorization": auth_header,
        "Accept": "application/json, text/event-stream",
    }
    mcp_timeout = httpx.Timeout(timeout_s, read=sse_read_s)

    def _httpx_factory(headers=None, timeout=None, auth=None) -> httpx.AsyncClient:
        merged = {**mcp_base_headers, **(headers or {})}
        return httpx.AsyncClient(
            headers=merged,
            timeout=timeout or mcp_timeout,
            auth=auth,
            follow_redirects=True,
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=0),
        )

    return MultiServerMCPClient(
        {
            "dremio": {
                "url": mcp_url,
                "transport": "streamable_http",
                "timeout": timedelta(seconds=timeout_s),
                "sse_read_timeout": timedelta(seconds=sse_read_s),
                "httpx_client_factory": _httpx_factory,
            }
        },
    )


async def load_mcp_tools(auth_header: str) -> list[Any]:
    """Connect to MCP server and return the discovered tool list.

    Results are cached per ``auth_header`` for ``DREMIO_MCP_TOOLS_CACHE_TTL`` seconds
    (default 300). Set the env var to 0 to disable caching.
    """
    mcp_url = _env("DREMIO_MCP_URL", "http://127.0.0.1:8080/mcp/")
    ttl = float(_env("DREMIO_MCP_TOOLS_CACHE_TTL", str(_TOOLS_CACHE_TTL_DEFAULT)))

    if ttl > 0:
        cached = _tools_cache.get(auth_header)
        if cached is not None:
            tools, ts = cached
            if time.monotonic() - ts < ttl:
                logger.info("MCP tools cache hit (%d tools)", len(tools))
                return tools

    try:
        client = build_mcp_client(auth_header)
        tools = await client.get_tools(server_name="dremio")
    except Exception as e:
        flat = _flatten_exception_message(e)
        logger.warning("Failed to load MCP tools at %s: %s", mcp_url, flat)
        raise RuntimeError(f"{flat} (MCP URL: {mcp_url})") from e
    logger.info("Loaded %d tools from Dremio MCP", len(tools))

    if ttl > 0:
        _tools_cache[auth_header] = (tools, time.monotonic())

    return tools
