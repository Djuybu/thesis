#!/usr/bin/env bash
#
# Copyright (C) 2017-2025 Dremio Corporation
#
# Launch the Dremio MCP server (Streaming HTTP) from services/chatbot/.
# The actual code lives in tools/dremio-mcp; this wrapper just feeds it the
# right config file (mcp-oss.yaml in this directory).
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
MCP_DIR="$REPO_ROOT/tools/dremio-mcp"

CFG="${MCP_CONFIG_FILE:-$HERE/mcp-oss.yaml}"
PORT="${MCP_PORT:-8080}"
EXTRA_ARGS=("$@")

if [[ ! -f "$CFG" ]]; then
  echo "Config file not found: $CFG" >&2
  echo "Copy mcp-oss.yaml.example to mcp-oss.yaml and fill in uri/pat." >&2
  exit 1
fi

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install: https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

echo "[services/chatbot] starting dremio-mcp-server on :$PORT with $CFG"
exec uv run --directory "$MCP_DIR" dremio-mcp-server run \
  -c "$CFG" \
  --enable-streaming-http \
  --port "$PORT" \
  --no-log-to-file \
  "${EXTRA_ARGS[@]}"
