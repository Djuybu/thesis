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

"""Accumulate LLM token usage via LangChain callbacks (OpenAI, Gemini, Ollama)."""

from __future__ import annotations

from typing import Any

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult


def _zero_counts() -> dict[str, int]:
    return {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0}


def _merge_counts(target: dict[str, int], inp: int, out: int, tot: int) -> None:
    target["input_tokens"] = target.get("input_tokens", 0) + max(0, inp)
    target["output_tokens"] = target.get("output_tokens", 0) + max(0, out)
    if tot > 0:
        target["total_tokens"] = target.get("total_tokens", 0) + tot
    elif inp or out:
        target["total_tokens"] = target.get("total_tokens", 0) + inp + out


def _usage_from_mapping(raw: Any) -> tuple[int, int, int]:
    if not raw:
        return 0, 0, 0
    if isinstance(raw, dict):
        inp = int(
            raw.get("input_tokens")
            or raw.get("prompt_tokens")
            or raw.get("prompt_token_count")
            or 0
        )
        out = int(
            raw.get("output_tokens")
            or raw.get("completion_tokens")
            or raw.get("candidates_token_count")
            or 0
        )
        tot = int(raw.get("total_tokens") or raw.get("total_token_count") or 0)
        return inp, out, tot
    inp = int(getattr(raw, "input_tokens", 0) or getattr(raw, "prompt_tokens", 0) or 0)
    out = int(
        getattr(raw, "output_tokens", 0)
        or getattr(raw, "completion_tokens", 0)
        or 0
    )
    tot = int(getattr(raw, "total_tokens", 0) or 0)
    return inp, out, tot


def parse_llm_result_usage(response: LLMResult) -> tuple[int, int, int]:
    """Extract token counts from an ``on_llm_end`` response."""
    inp = out = tot = 0
    llm_output = response.llm_output or {}
    if isinstance(llm_output, dict):
        tu = llm_output.get("token_usage") or llm_output.get("usage") or {}
        i, o, t = _usage_from_mapping(tu)
        inp += i
        out += o
        tot += t
        # Ollama eval_count / prompt_eval_count
        pe = int(llm_output.get("prompt_eval_count") or 0)
        ec = int(llm_output.get("eval_count") or 0)
        if pe or ec:
            inp += pe
            out += ec
            tot += pe + ec

    for gen_list in response.generations or []:
        for gen in gen_list:
            msg = getattr(gen, "message", None)
            if msg is None:
                continue
            um = getattr(msg, "usage_metadata", None)
            i, o, t = _usage_from_mapping(um)
            inp += i
            out += o
            tot += t
            rm = getattr(msg, "response_metadata", None) or {}
            if isinstance(rm, dict):
                tu = rm.get("token_usage") or rm.get("usage_metadata") or {}
                i, o, t = _usage_from_mapping(tu)
                inp += i
                out += o
                tot += t

    if tot <= 0 and (inp or out):
        tot = inp + out
    return inp, out, tot


class AgentTokenTracker(BaseCallbackHandler):
    """Tracks tokens per graph node; set active node from the streaming collector."""

    def __init__(self) -> None:
        self.total = _zero_counts()
        self.by_node: dict[str, dict[str, int]] = {}
        self._active_node = "llm"

    def set_active_node(self, node_name: str) -> None:
        name = (node_name or "llm").strip() or "llm"
        self._active_node = name
        self.by_node.setdefault(name, _zero_counts())

    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        inp, out, tot = parse_llm_result_usage(response)
        if not (inp or out or tot):
            return
        _merge_counts(self.total, inp, out, tot)
        node = self._active_node
        bucket = self.by_node.setdefault(node, _zero_counts())
        _merge_counts(bucket, inp, out, tot)

    def snapshot(self) -> dict[str, Any]:
        return {
            "total": dict(self.total),
            "steps": {k: dict(v) for k, v in self.by_node.items()},
        }
