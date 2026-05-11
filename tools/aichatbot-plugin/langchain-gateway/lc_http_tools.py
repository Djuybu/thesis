# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""Allowlisted HTTP GET tool for the ReAct agent."""

from __future__ import annotations

import json
from typing import Any
import httpx
from langchain_core.tools import tool

from lc_config import env


def _allowed_url(url: str, prefixes: list[str]) -> bool:
    u = url.strip()
    if not u.startswith(("http://", "https://")):
        return False
    for p in prefixes:
        pre = p.strip().rstrip("/")
        if pre and u.startswith(pre):
            return True
    return False


def build_http_get_tools() -> list[Any]:
    raw = env("GATEWAY_HTTP_TOOL_ALLOWLIST", "")
    if not raw.strip():
        return []
    prefixes = [x.strip() for x in raw.split(",") if x.strip()]
    if not prefixes:
        return []

    @tool
    async def http_get_allowlisted(url: str) -> str:
        """Fetch JSON or text from an allowlisted HTTP GET URL. Use for internal APIs only."""
        url = url.strip()
        if not _allowed_url(url, prefixes):
            return json.dumps(
                {
                    "error": "URL not allowed",
                    "hint": "Configure GATEWAY_HTTP_TOOL_ALLOWLIST with comma-separated URL prefixes.",
                }
            )
        timeout_s = float(env("GATEWAY_HTTP_TOOL_TIMEOUT_SECONDS", "30"))
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.get(url)
            ct = (r.headers.get("content-type") or "").lower()
            text = r.text[:8000]
            if "application/json" in ct:
                try:
                    return json.dumps(r.json())[:8000]
                except Exception:
                    pass
            return f"status={r.status_code}\ncontent-type={ct}\nbody={text}"

    return [http_get_allowlisted]
