# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""Chat history keyed by session and optional user id (persistent Redis or process memory)."""

from __future__ import annotations

from dataclasses import dataclass

from fastapi import Request
from langchain_community.chat_message_histories import RedisChatMessageHistory
from langchain_core.chat_history import BaseChatMessageHistory, InMemoryChatMessageHistory

from lc_config import env


@dataclass(frozen=True)
class TenantContext:
    """Stable keys for memory + RAG indices."""

    history_key: str
    session_id: str
    user_id: str | None
    rag_tenant_id: str


_in_memory_history: dict[str, InMemoryChatMessageHistory] = {}


def resolve_tenant(http_request: Request, session_id_raw: str | None, user_id_raw: str | None) -> TenantContext:
    sid = (session_id_raw or http_request.headers.get("X-Chat-Session-Id") or http_request.headers.get("x-chat-session-id") or "default").strip() or "default"
    uid_header = http_request.headers.get("X-User-Id") or http_request.headers.get("x-user-id")
    uid = (user_id_raw or uid_header or "").strip() or None
    if uid:
        history_key = f"user:{uid}:session:{sid}"
        rag_tenant_id = f"user:{uid}"
    else:
        history_key = sid
        rag_tenant_id = sid
    return TenantContext(
        history_key=history_key,
        session_id=sid,
        user_id=uid,
        rag_tenant_id=rag_tenant_id,
    )


def get_history_store(history_key: str) -> tuple[BaseChatMessageHistory, str]:
    redis_url = env("GATEWAY_REDIS_URL", "")
    if redis_url:
        key_prefix = env("GATEWAY_HISTORY_KEY_PREFIX", "aichat:")
        ttl_s = int(env("GATEWAY_HISTORY_TTL_SECONDS", "0"))
        try:
            if ttl_s > 0:
                return (
                    RedisChatMessageHistory(
                        session_id=history_key,
                        url=redis_url,
                        key_prefix=key_prefix,
                        ttl=ttl_s,
                    ),
                    "redis",
                )
            return (
                RedisChatMessageHistory(
                    session_id=history_key,
                    url=redis_url,
                    key_prefix=key_prefix,
                ),
                "redis",
            )
        except TypeError:
            return (
                RedisChatMessageHistory(
                    session_id=history_key,
                    url=redis_url,
                    key_prefix=key_prefix,
                ),
                "redis",
            )

    if history_key not in _in_memory_history:
        _in_memory_history[history_key] = InMemoryChatMessageHistory()
    return _in_memory_history[history_key], "memory"
