#!/usr/bin/env bash
#
# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Build artifacts needed to test the AI chatbot plugin + LangChain gateway locally.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "==> Maven: tools/aichatbot-plugin (skip ErrorProne — artifact nội bộ Dremio)"
./mvnw -pl tools/aichatbot-plugin -DskipTests -Derrorprone.skip=true package

echo ""
echo "==> JAR:"
ls -la tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar

GW="tools/aichatbot-plugin/langchain-gateway"
if command -v python3 >/dev/null 2>&1; then
  echo ""
  echo "==> LangChain gateway venv + pip"
  if python3 -m venv "$GW/.venv" 2>/dev/null; then
    # shellcheck source=/dev/null
    source "$GW/.venv/bin/activate"
    pip install -U pip -q
    pip install -r "$GW/requirements.txt"
    echo "Gateway deps OK. Kích hoạt: source $GW/.venv/bin/activate"
  else
    echo "Không tạo được venv (cần gói python3-venv trên Debian/Ubuntu):"
    echo "  sudo apt install python3-venv"
    echo "Sau đó chạy lại script hoặc:"
    echo "  cd $GW && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
  fi
fi

echo ""
echo "Chạy plugin (ví dụ):"
echo "  export DREMIO_BASE_URL=http://localhost:9047"
echo "  export AI_BACKEND_URL=http://127.0.0.1:11434/v1/chat/completions"
echo "  export AI_MODEL_DEFAULT=qwen3.5:9b"
echo "  java -jar tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar"
echo ""
echo "Chạy gateway (sau khi có .venv):"
echo "  cd $GW && source .venv/bin/activate"
echo "  export OLLAMA_BASE_URL=http://127.0.0.1:11434 && export OLLAMA_MODEL=qwen3.5:9b"
echo "  python -m uvicorn gateway_app:app --host 127.0.0.1 --port 9292"
