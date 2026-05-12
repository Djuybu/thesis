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

"""Shared environment helpers for the gateway."""

from __future__ import annotations

import os

# Single default for gateway + `/aichat/v1/config` when `OLLAMA_MODEL` is unset.
# Was ``qwen3.5:4b`` — but Qwen 3.5 runs in "thinking" mode by default and
# Ollama emits ~thousands of reasoning tokens before any JSON content for
# ``with_structured_output`` calls, causing ``sql_gen_node`` to time out even
# after 900s on CPU (verified via direct Ollama probe: only 200 of 200 tokens
# went into ``thinking``, ``content`` stayed empty). ``langchain-ollama`` 0.3.0
# has no pass-through for Ollama's ``think: false`` request body field, so we
# pick ``qwen2.5:3b`` (no reasoning mode) which produces valid structured SQL
# in ~11s on the same hardware. Users with a GPU can override via the
# ``OLLAMA_MODEL`` env var.
DEFAULT_OLLAMA_MODEL = "qwen2.5:3b"


def env(name: str, default: str = "") -> str:
    v = os.environ.get(name)
    return v.strip() if v and v.strip() else default


def dremio_rest_base_url() -> str:
    """Base URL for direct gateway → Dremio REST calls (same host as MCP ``dremio.uri``)."""
    return env("DREMIO_URI", "http://localhost:9047")


def normalize_auth_header(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    if s.lower().startswith("bearer "):
        return s
    return f"Bearer {s}"
