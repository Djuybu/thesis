#!/usr/bin/env bash
# Start the LangChain HTTP gateway (Ollama + MCP via aichatbot mcp-proxy).
set -e

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
GW=$(cd "${HERE}/.." && pwd)
cd "$GW"

# Load environment overrides from langchain-gateway/.env when present.
if [ -f "$GW/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$GW/.env"
  set +a
fi

PY="$GW/.venv/bin/python"
if [ ! -f "$PY" ]; then
  echo "Missing $PY — run scripts/setup-venv.sh first." >&2
  exit 1
fi

export GATEWAY_HOST=${GATEWAY_HOST:-"127.0.0.1"}
export GATEWAY_PORT=${GATEWAY_PORT:-"9292"}
export OLLAMA_MODEL=${OLLAMA_MODEL:-"gemma4:e4b"}
export OLLAMA_BASE_URL=${OLLAMA_BASE_URL:-"http://127.0.0.1:11434"}
export AICHAT_MCP_PROXY_URL=${AICHAT_MCP_PROXY_URL:-"http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp/"}

echo "Gateway http://$GATEWAY_HOST:$GATEWAY_PORT  MCP proxy: $AICHAT_MCP_PROXY_URL  Ollama model: $OLLAMA_MODEL"
exec "$PY" -m uvicorn gateway_app:app --host "$GATEWAY_HOST" --port "$GATEWAY_PORT"
