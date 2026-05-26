#
#  Copyright (C) 2017-2025 Dremio Corporation
#

from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, LLMResult

from dremioai.gateway.token_tracking import AgentTokenTracker, parse_llm_result_usage


def test_parse_openai_style_usage():
    result = LLMResult(
        generations=[[ChatGeneration(message=AIMessage(content="hi", usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}))]],
    )
    inp, out, tot = parse_llm_result_usage(result)
    assert inp == 10
    assert out == 5
    assert tot == 15


def test_tracker_accumulates_by_node():
    tracker = AgentTokenTracker()
    tracker.set_active_node("sql_gen")
    result = LLMResult(
        generations=[[ChatGeneration(message=AIMessage(content="x", usage_metadata={"input_tokens": 100, "output_tokens": 20, "total_tokens": 120}))]],
    )
    tracker.on_llm_end(result)
    snap = tracker.snapshot()
    assert snap["total"]["input_tokens"] == 100
    assert snap["steps"]["sql_gen"]["output_tokens"] == 20
