# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""Shared env helpers for the LangChain gateway."""

from __future__ import annotations


def env(name: str, default: str) -> str:
    import os

    v = os.environ.get(name)
    return v.strip() if v and v.strip() else default


def normalize_auth_header(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    if s.lower().startswith("bearer "):
        return s
    return f"Bearer {s}"
