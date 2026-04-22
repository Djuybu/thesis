#!/usr/bin/env bash
# Example: run dremio-mcp in streaming HTTP mode (clone repo first; see README).
set -e

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${HERE}/../../../.." && pwd)
MCP_ROOT="$REPO_ROOT/tools/dremio-mcp"

if [ ! -d "$MCP_ROOT" ]; then
  echo "Expected dremio-mcp at $MCP_ROOT — git clone https://github.com/dremio/dremio-mcp.git tools/dremio-mcp" >&2
  exit 1
fi

CONFIG=${1:-"$HERE/../example-mcp-oss.yaml"}
CONFIG=$(realpath "$CONFIG")

echo "Using config: $CONFIG"
echo "From $MCP_ROOT run (with uv installed):"
echo "  uv run dremio-mcp-server run -c \"$CONFIG\" --enable-streaming-http --port 8000 --host 127.0.0.1"

cd "$MCP_ROOT"
if command -v uv >/dev/null 2>&1; then
  exec uv run dremio-mcp-server run -c "$CONFIG" --enable-streaming-http --port 8000 --host 127.0.0.1
else
  echo "Install uv (https://docs.astral.sh/uv/) or run the printed command manually from tools/dremio-mcp." >&2
  exit 1
fi
