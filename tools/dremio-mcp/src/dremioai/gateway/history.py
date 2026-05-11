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

"""Chat history keyed by session and optional user id (Redis or in-process memory)."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from fastapi import Request

from dremioai.gateway.config import env


@dataclass(frozen=True)
class TenantContext:
    history_key: str
    session_id: str
    user_id: str | None


def resolve_tenant(
    http_request: Request,
    session_id_raw: str | None,
    user_id_raw: str | None,
) -> TenantContext:
    sid = (
        session_id_raw
        or http_request.headers.get("X-Chat-Session-Id")
        or ""
    ).strip()
    uid = (user_id_raw or http_request.headers.get("X-User-Id") or "").strip() or None

    if not sid or sid == "default":
        if uid:
            sid = f"user:{uid}"
        else:
            auth = (http_request.headers.get("authorization") or "").strip()
            if auth:
                token_hash = hashlib.sha256(auth.encode()).hexdigest()[:24]
                sid = f"tok:{token_hash}"
            else:
                sid = "default"

    history_key = f"user:{uid}:session:{sid}" if uid else sid
    return TenantContext(history_key=history_key, session_id=sid, user_id=uid)
