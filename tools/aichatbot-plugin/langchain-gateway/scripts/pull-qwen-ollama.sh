#!/usr/bin/env bash
# Pulls Qwen 3.5 9B model for local testing (requires Ollama installed).
set -e

# Override when needed, e.g. OLLAMA_MODEL=gemma3:4b bash pull-qwen-ollama.sh
MODEL=${OLLAMA_MODEL:-"qwen3.5:4b"}

if ! command -v ollama >/dev/null 2>&1; then
  echo -e "\033[33mOllama not found on PATH. Install from https://ollama.com then run:\033[0m"
  echo "  ollama pull $MODEL"
  exit 1
fi

echo "Pulling $MODEL ..."
ollama pull "$MODEL"
echo "Done. Gateway can now use model: $MODEL"
echo "Tip: export OLLAMA_MODEL=$MODEL"
