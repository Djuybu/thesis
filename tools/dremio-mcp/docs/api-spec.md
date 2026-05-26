# Dremio SQL Agent — Gateway API Specification (v1)

## Overview

HTTP gateway for the LangGraph-based SQL agent with human-in-the-loop (HITL).
Replaces the legacy `aichatbot-plugin` Java server and `langchain-gateway` Python process
with a single FastAPI service running inside `dremio-mcp`.

## Base path

All endpoints are served under `/aichat/` so the existing DAC reverse-proxy
(`/aichat/* → gateway base URL`) works without changes.

---

## Endpoints

### `GET /aichat/health`

Health check.

**Response** `200`:
```json
{ "status": "ok", "service": "dremio-sql-agent" }
```

### `GET /aichat/v1/config`

Service configuration (consumed by DAC UI on startup).

**Response** `200`:
```json
{
  "service": "dremio-sql-agent",
  "version": "1.0.0",
  "default_model": "gpt-4o-mini",
  "llm_provider": "openai",
  "api_key_configured": true,
  "hitl_enabled": true,
  "guardrail_enabled": true,
  "mcp_configured": true
}
```

`llm_provider` is `ollama` or `openai`. `api_key_configured` is true when an OpenAI-compatible key is set (never exposes the key).

### `POST /aichat/v1/chat`

Start a new chat thread. The graph runs through guardrail → discovery →
metadata_confirmation (HITL interrupt) → sql_gen → refinement (HITL interrupt) → execute.

**Request** (JSON):
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | string | yes | User question |
| `thread_id` | string | no | Stable thread id; generated if omitted |
| `session_id` | string | no | Chat session id for history isolation |
| `user_id` | string | no | Logical user id |
| `user_context` | string | no | Extra context (e.g. Dremio profile JSON) |
| `model` | string | no | Model id (default per `LLM_PROVIDER`: `OLLAMA_MODEL`, `OPENAI_MODEL`, `GEMINI_MODEL`) |

**Response** `200`:
```json
{
  "status": "interrupted | completed | error",
  "thread_id": "hitl-abc123",
  "model": "qwen3.5:4b",
  "node": "metadata_confirmation | refinement | null",
  "interrupt": { ... },
  "answer": "...",
  "execution_result": { ... },
  "error": "...",
  "elapsed_ms": 45230,
  "step_timings_ms": { "sql_gen": 8500, "finalize": 2100, "execute": 3100 },
  "token_usage": { "input_tokens": 3200, "output_tokens": 450, "total_tokens": 3650 },
  "step_token_usage": {
    "sql_gen": { "input_tokens": 1800, "output_tokens": 120, "total_tokens": 1920 },
    "finalize": { "input_tokens": 900, "output_tokens": 200, "total_tokens": 1100 }
  }
}
```

Metrics (optional; enabled by default via `AGENT_STEP_TIMINGS` / `AGENT_TOKEN_USAGE`):

- `elapsed_ms` — wall-clock for this HTTP agent run (milliseconds).
- `step_timings_ms` — time per LangGraph node name.
- `token_usage` — summed LLM tokens for this run (`input_tokens`, `output_tokens`, `total_tokens`). May be zero for Ollama models that do not report usage.
- `step_token_usage` — same token fields keyed by node (`guardrail`, `sql_gen`, `finalize`, …).

**Run log file (JSONL):** when `AGENT_RUN_LOG=1` (default via `run-gateway.sh`), each `/chat` and `/resume` appends one JSON line to `AGENT_RUN_LOG_PATH` (default `services/chatbot/logs/agent-runs.jsonl`) with the same fields plus `user_message`, `llm_provider`, `proposed_sql`, etc. Set `AGENT_RUN_LOG=0` to disable.

- `status=interrupted`: graph paused at a HITL node; `interrupt` contains the payload
  for the UI to render (discovered metadata or proposed SQL).
- `status=completed`: graph finished; `answer` + optional `execution_result`.
- `status=error`: graph failed; `error` has the message.

### `POST /aichat/v1/chat/resume`

Resume a paused thread after the user reviews metadata or SQL.

**Request** (JSON):
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `thread_id` | string | yes | Thread to resume |
| `action` | string | yes | One of: `approve`, `reject`, `edit` |
| `payload` | object | no | For `edit`: `{ "sql": "SELECT ..." }` |

**Response**: same shape as `/aichat/v1/chat`.

---

## Authentication

All endpoints expect `Authorization: Bearer <token>` forwarded from DAC.
The gateway validates the token against Dremio (`/apiv2/login`) before processing.

## HITL interrupt payloads

### `metadata_confirmation` node
```json
{
  "action": "metadata_confirmation",
  "message": "Review discovered tables/schemas before SQL generation.",
  "tables": [ ... ],
  "schemas": { "table_fqn": { ... } }
}
```

### `refinement` node
```json
{
  "action": "sql_approval",
  "message": "Review the proposed SQL before execution.",
  "table_fqn": "source.schema.table",
  "proposed_sql": "SELECT ...",
  "rationale": "..."
}
```
