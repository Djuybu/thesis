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

"""Locale helpers for user-facing LLM text (summaries, explanations)."""

from __future__ import annotations

import os
import re

_VI_CHARS_RE = re.compile(
    r"[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]"
    r"|(?:\b(xin chao|chao ban|cam on|hay|duoc|khong|bang may|bao nhieu|chuyen xe|bang|moi ta|tom tat|ket qua)\b)",
    re.I,
)

# Common English leakage from small local models (e.g. Ollama qwen2.5:3b) on finalize.
_ENGLISH_FINALIZE_MARKERS = re.compile(
    r"(?i)\b("
    r"the query result|here are the details|from the dataset|"
    r"if you need more specific|shows the top|each entry includes|"
    r"please let me know|corresponding fare|highest fare amounts"
    r")\b",
)


def agent_response_locale() -> str:
    """``AGENT_RESPONSE_LOCALE``: ``vi`` (default), ``en``, or ``auto``."""
    raw = os.environ.get("AGENT_RESPONSE_LOCALE", "vi").strip().lower()
    if raw in ("en", "english"):
        return "en"
    if raw in ("auto", ""):
        return "auto"
    return "vi"


def _llm_provider_from_env() -> str:
    from dremioai.gateway.llm_factory import resolve_llm_provider

    return resolve_llm_provider()


def llm_uses_no_think_prefix() -> bool:
    """Ollama Qwen builds may need ``/no_think``; OpenAI-compatible APIs do not."""
    return _llm_provider_from_env() == "ollama"


def user_prefers_vietnamese(user_question: str) -> bool:
    loc = agent_response_locale()
    if loc == "vi":
        return True
    if loc == "en":
        return False
    return bool(_VI_CHARS_RE.search(user_question or ""))


def user_facing_language_clause(user_question: str) -> str:
    """System-prompt fragment: language for end-user prose (not SQL identifiers)."""
    if user_prefers_vietnamese(user_question):
        return (
            "BẮT BUỘC: Trả lời người dùng bằng tiếng Việt, ngắn gọn, dễ hiểu. "
            "Không dùng tiếng Anh cho phần giải thích (trừ tên cột/bảng/SQL giữ nguyên). "
            "Giữ nguyên tên bảng, cột và giá trị như trong dữ liệu. "
            "Có thể dùng gạch đầu dòng markdown khi liệt kê."
        )
    return (
        "Answer in English in clear, concise prose. "
        "Keep table/column names and SQL identifiers exactly as in the data."
    )


def hitl_metadata_message() -> str:
    if agent_response_locale() == "en":
        return "Review discovered tables and schema before SQL generation."
    return "Xem lại bảng và schema đã tìm được trước khi sinh SQL."


def hitl_sql_approval_message() -> str:
    if agent_response_locale() == "en":
        return "Review the proposed SQL before execution on Dremio."
    return "Xem và phê duyệt câu SQL trước khi chạy trên Dremio."


def finalize_system_message(user_question: str, *, retry: bool = False) -> str:
    """Full system prompt for the post-query summarization step."""
    prefix = "/no_think\n" if llm_uses_no_think_prefix() else ""
    if user_prefers_vietnamese(user_question):
        retry_note = (
            " QUAN TRỌNG: Lần trước bạn đã trả lời bằng tiếng Anh — lần này CHỈ dùng tiếng Việt."
            if retry
            else ""
        )
        return (
            f"{prefix}"
            "Bạn là trợ lý phân tích dữ liệu Dremio (giống giao diện Enterprise). "
            f"{retry_note}"
            "Nhiệm vụ: tóm tắt kết quả truy vấn SQL cho người dùng. "
            "BẮT BUỘC: toàn bộ phần giải thích bằng tiếng Việt; không viết tiếng Anh "
            "(trừ tên cột, bảng, giá trị và mã SQL giữ nguyên). "
            "Nêu số dòng trả về, các cột chính, và điểm nổi bật (ví dụ top N, min/max). "
            "Nếu JSON có trường error, giải thích lỗi và gợi ý sửa bằng tiếng Việt. "
            "Dùng gạch đầu dòng hoặc bảng markdown khi liệt kê."
        )
    return (
        f"{prefix}"
        "You are a Dremio data assistant. Summarize the SQL query result for the user "
        "in clear English. Keep table/column names and values as in the data. "
        "If there is an error field, explain what went wrong and suggest a fix."
    )


def finalize_user_message(
    *,
    user_question: str,
    final_sql: str | None,
    result_json: str,
) -> str:
    """Human message for finalize — Vietnamese instructions when locale is vi."""
    if user_prefers_vietnamese(user_question):
        return (
            f"Câu hỏi của người dùng:\n{user_question}\n\n"
            f"SQL đã chạy:\n{final_sql or ''}\n\n"
            f"Kết quả (JSON):\n{result_json}\n\n"
            "Hãy tóm tắt kết quả trên bằng tiếng Việt cho người dùng."
        )
    return (
        f"User question:\n{user_question}\n\n"
        f"SQL:\n{final_sql or ''}\n\n"
        f"Result:\n{result_json}\n\n"
        "Summarize the result for the user."
    )


def answer_looks_english_not_vietnamese(text: str) -> bool:
    """Heuristic: finalize answered in English while Vietnamese was required."""
    if not (text or "").strip():
        return False
    if _VI_CHARS_RE.search(text):
        return False
    if _ENGLISH_FINALIZE_MARKERS.search(text):
        return True
    english_words = len(
        re.findall(
            r"\b(the|and|with|from|your|query|result|shows|trips|dataset)\b",
            text,
            re.I,
        )
    )
    return english_words >= 4
