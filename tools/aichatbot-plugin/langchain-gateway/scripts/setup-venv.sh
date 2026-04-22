#!/usr/bin/env bash
# Run from repository root (dremio-oss) or from this script's directory.
set -e

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
GW=$(cd "${HERE}/.." && pwd)
cd "$GW"

if [ ! -d ".venv" ]; then
  python3 -m venv .venv
fi

"$GW/.venv/bin/pip" install -r requirements.txt
echo "venv ready at $GW/.venv — activate: source .venv/bin/activate"
