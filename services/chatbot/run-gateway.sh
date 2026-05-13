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

echo "[services/chatbot] starting dremio-sql-agent on :${GATEWAY_PORT:-9292}"
echo "  DREMIO_MCP_URL=${DREMIO_MCP_URL:-<unset>}"
echo "  OLLAMA_MODEL=${OLLAMA_MODEL:-<unset>}"

exec uv run --directory "$MCP_DIR" dremio-sql-agent "${EXTRA_ARGS[@]}"
