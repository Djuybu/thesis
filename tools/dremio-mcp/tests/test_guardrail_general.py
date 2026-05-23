"""Unit tests for general-question guardrail (math / out-of-catalog)."""

from __future__ import annotations

from dremioai.agent.nodes.guardrail import (
    _format_general_answer,
    _looks_like_general_question,
    route_after_guardrail,
)


def test_simple_math_detected() -> None:
    assert _looks_like_general_question("1+1 bằng mấy")
    assert _looks_like_general_question("2 * 3 là bao nhiêu")


def test_data_question_not_general() -> None:
    assert not _looks_like_general_question("đếm số dòng trong bảng SF_incidents2016")
    assert not _looks_like_general_question("liệt kê các bảng trong Samples")


def test_format_simple_addition() -> None:
    ans = _format_general_answer("1+1 bằng mấy")
    assert "1 + 1 = 2" in ans
    assert "Dremio" in ans


def test_route_general_skips_discovery() -> None:
    nxt = route_after_guardrail(
        {
            "is_safe": True,
            "is_greeting": False,
            "is_general": True,
        }
    )
    assert nxt == "general_reply"
