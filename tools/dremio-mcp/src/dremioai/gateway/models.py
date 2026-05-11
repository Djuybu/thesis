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

"""Pydantic request/response models for the gateway API v1."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class ChatStartRequest(BaseModel):
    """Start-chat body.

    Supports v1 names (``message``, ``thread_id``) and legacy Java plugin names
    (``prompt``, ``sessionId`` on ``POST /aichat/ask``).
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    message: str = Field(
        ...,
        min_length=1,
        validation_alias=AliasChoices("message", "prompt"),
    )
    thread_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("thread_id", "sessionId"),
    )
    session_id: str | None = None
    user_id: str | None = None
    user_context: str | None = None
    model: str | None = None


class ChatResumeRequest(BaseModel):
    thread_id: str = Field(..., min_length=1)
    action: Literal["approve", "reject", "edit"] = "approve"
    payload: dict[str, Any] | None = None


class ChatResponse(BaseModel):
    status: Literal["interrupted", "completed", "error"]
    thread_id: str
    model: str
    node: str | None = None
    interrupt: dict[str, Any] | list[dict[str, Any]] | None = None
    answer: str | None = None
    execution_result: Any | None = None
    error: str | None = None


class ConfigResponse(BaseModel):
    service: str = "dremio-sql-agent"
    version: str = "1.0.0"
    default_model: str = ""
    hitl_enabled: bool = True
    guardrail_enabled: bool = True
    mcp_configured: bool = False
