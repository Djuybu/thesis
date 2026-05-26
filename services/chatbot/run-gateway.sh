#!/usr/bin/env bash
#
# Copyright (C) 2017-2025 Dremio Corporation
#
# Launch the Dremio SQL Agent gateway (FastAPI) from services/chatbot/.
# Loads .env (if present) and delegates to `dremio-sql-agent` in tools/dremio-mcp.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
MCP_DIR="$REPO_ROOT/tools/dremio-mcp"

if [[ -f "$HERE/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$HERE/.env"
  set +a
fi

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install: https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

EXTRA_ARGS=("$@")

case "${AGENT_RUN_LOG:-1}" in
  0|false|no|off) ;;
  *)
    export AGENT_RUN_LOG_PATH="${AGENT_RUN_LOG_PATH:-$HERE/logs/agent-runs.jsonl}"
    mkdir -p "$(dirname "$AGENT_RUN_LOG_PATH")"
    ;;
esac

echo "[services/chatbot] starting dremio-sql-agent on :${GATEWAY_PORT:-9292}"
echo "  DREMIO_MCP_URL=${DREMIO_MCP_URL:-<unset>}"
case "${LLM_PROVIDER:-}" in
  gemini|google|google_genai)
    _PROV=gemini ;;
  openai|openai_compatible|azure|openrouter|groq)
    _PROV=openai ;;
  ollama|local)
    _PROV=ollama ;;
  *)
    if [[ -n "${GEMINI_API_KEY:-}" || -n "${GOOGLE_API_KEY:-}" ]]; then
      _PROV=gemini
    elif [[ -n "${OPENAI_API_KEY:-}" || -n "${LLM_API_KEY:-}" ]]; then
      _PROV=openai
    else
      _PROV=ollama
    fi ;;
esac

echo "  LLM_PROVIDER=${LLM_PROVIDER:-${_PROV} (auto)}"
case "$_PROV" in
  gemini)
    echo "  GEMINI_MODEL=${GEMINI_MODEL:-gemini-2.0-flash}"
    echo "  GEMINI_API_KEY=<set>"
    ;;
  openai)
    echo "  OPENAI_MODEL=${OPENAI_MODEL:-${LLM_MODEL:-gpt-4o-mini}}"
    echo "  OPENAI_BASE_URL=${OPENAI_BASE_URL:-${LLM_BASE_URL:-<default>}}"
    echo "  OPENAI_API_KEY=<set>"
    ;;
  *)
    echo "  OLLAMA_MODEL=${OLLAMA_MODEL:-<unset>}"
    echo "  OLLAMA_BASE_URL=${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
    ;;
esac

if [[ -n "${AGENT_RUN_LOG_PATH:-}" ]]; then
  echo "  AGENT_RUN_LOG_PATH=${AGENT_RUN_LOG_PATH}  (một dòng JSON / mỗi lần hỏi hoặc resume)"
fi

exec uv run --directory "$MCP_DIR" dremio-sql-agent "${EXTRA_ARGS[@]}"
