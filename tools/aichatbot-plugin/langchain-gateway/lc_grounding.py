# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""Optional prompt blocks: strict grounding + structured data-query workflow (discover → schema → confirm → SQL)."""

from __future__ import annotations

from lc_config import env

# English: matches default LLM system prompts; keeps tool-calling models aligned with MCP/Dremio.
STRICT_GROUNDING_BLOCK = """
Grounding and accuracy (strict):
- Dremio / data: Use MCP tools to find tables, views, and column names before writing SQL. Do not invent
  table, schema, or column names that did not appear in tool outputs, user_context, or this chat after
  a metadata tool was used. If discovery returns nothing useful, say so and ask for a dataset path or name
  instead of guessing.
- SQL: Only use identifiers you have seen in recent tool results (or exact strings from user_context).
  Prefer LIMIT on exploratory SELECTs. If a query errors, fix using catalog facts—do not fabricate columns.
- Uploaded PDFs: For substantive claims about document content, call search_uploaded_documents first.
  If the tool returns no passages, do not state detailed facts from the document.
- Uncertainty: If data is missing or ambiguous, say you are uncertain rather than filling gaps from general
  knowledge. Do not present invented metrics, row counts, or entity names.
- Final answers should summarize tool outputs; treat tools as the source of truth for data and catalog facts.
- User-facing replies must be in Vietnamese, focused on what was asked: do not paste entire schemas when the
  question targets a subset (time, money, location, counts, etc.); never reply with generic “please ask a question”
  if the user already asked something specific.
""".strip()


def strict_grounding_env_default() -> bool:
    return env("GATEWAY_STRICT_GROUNDING", "true").strip().lower() in ("1", "true", "yes", "on")


def append_strict_grounding(base_prompt: str, enabled: bool) -> str:
    if not enabled:
        return base_prompt
    b = base_prompt.rstrip()
    return b + "\n\n" + STRICT_GROUNDING_BLOCK


# Guides the model through table discovery, schema read, user confirmation, then SQL (still ReAct — not enforced in code).
DATA_QUERY_WORKFLOW_BLOCK = """
Dremio data questions (when the user asks for data, SQL, metrics, lists, or analytics from lakehouse tables — not questions only about uploaded PDFs):

Workflow — follow this order:
1) Discover: Use MCP catalog/search tools to find candidate table(s) or view(s) that match the question. Do not invent fully-qualified names.
2) Schema: For the object(s) you intend to query, fetch columns and types using MCP schema tools (so SQL uses real column names).
3) Confirm with the user before RunSqlQuery: In the user's language, one short sentence — which table/view and what you will query; ask if that matches (e.g. Vietnamese: "Tôi dùng bảng … — đúng ý bạn không?").
   You may skip asking only if: (a) user_context or the latest user message already gives the exact FQN you will use; or (b) chat history already shows the user confirmed this same table/view for the current task; or (c) the user's message is clearly an approval (yes / đúng / ok / chạy đi) right after you proposed a specific table in the previous assistant turn.
4) Query: After confirmation (or when skip is allowed), call RunSqlQuery with SELECT-oriented SQL; use LIMIT for exploration when appropriate. Final answer: only the result relevant to the question, in Vietnamese — not a full schema dump unless they asked for it.

If the user says it is the wrong table, return to discovery (step 1) with their clarification.
""".strip()


def data_query_workflow_env_default() -> bool:
    return env("GATEWAY_DATA_QUERY_WORKFLOW", "true").strip().lower() in (
        "1",
        "true",
        "yes",
        "on",
    )


def append_data_query_workflow(base_prompt: str, enabled: bool) -> str:
    if not enabled:
        return base_prompt
    b = base_prompt.rstrip()
    return b + "\n\n" + DATA_QUERY_WORKFLOW_BLOCK
