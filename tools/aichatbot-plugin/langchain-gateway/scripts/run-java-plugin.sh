#!/usr/bin/env bash
# Khởi động AI Chatbot Plugin (Dremio Java Plugin) cho Linux/macOS
set -e

# Xác định thư mục repo gốc (dremio-oss)
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${HERE}/../../../.." && pwd)
PLUGIN_DIR="$REPO_ROOT/tools/aichatbot-plugin"

cd "$REPO_ROOT"

# Tìm file JAR đã build
JAR_FILE=$(ls "$PLUGIN_DIR"/target/dremio-aichatbot-plugin-*.jar 2>/dev/null | head -n 1)

if [ -z "$JAR_FILE" ]; then
    echo "Không tìm thấy file dremio-aichatbot-plugin-*.jar."
    echo "Đang tiến hành build plugin bằng Maven..."
    ./mvnw -pl tools/aichatbot-plugin -DskipTests package
    JAR_FILE=$(ls "$PLUGIN_DIR"/target/dremio-aichatbot-plugin-*.jar 2>/dev/null | head -n 1)
    if [ -z "$JAR_FILE" ]; then
        echo "Lỗi: Vẫn không thể build hoặc tìm thấy file JAR!" >&2
        exit 1
    fi
fi

# Thiết lập các biến môi trường cấu hình (có thể ghi đè từ bên ngoài)
export DREMIO_BASE_URL="${DREMIO_BASE_URL:-http://localhost:9047}"
export AICHAT_PORT="${AICHAT_PORT:-9191}"

# Bật Endpoint proxy cho MCP Server (thay cổng 8000 đúng với MCP server của bạn nếu cần)
export DREMIO_MCP_HTTP_BASE="${DREMIO_MCP_HTTP_BASE:-http://127.0.0.1:8080}"
export DREMIO_MCP_HTTP_PATH="${DREMIO_MCP_HTTP_PATH:-/mcp/}"

# Cấu hình gọi Ollama dùng để hỏi đáp trực tiếp (nếu không qua Langchain thì sẽ dùng cấu hình này)
export AI_BACKEND_URL="${AI_BACKEND_URL:-http://127.0.0.1:11434/v1/chat/completions}"
export AI_MODEL_DEFAULT="${AI_MODEL_DEFAULT:-qwen2.5:3b}"

echo "============================================================"
echo "▶ Khởi động AI Chatbot Plugin (Standalone - Java)"
echo "▶ DREMIO_BASE_URL:      $DREMIO_BASE_URL"
echo "▶ AICHAT_PORT:          $AICHAT_PORT"
echo "▶ MCP_PROXY target:     $DREMIO_MCP_HTTP_BASE$DREMIO_MCP_HTTP_PATH"
echo "▶ AI_BACKEND_URL:       $AI_BACKEND_URL"
echo "▶ AI_MODEL_DEFAULT:     $AI_MODEL_DEFAULT"
echo "▶ File thi hành:        $JAR_FILE"
echo "============================================================"

# Chạy JAR
exec java -jar "$JAR_FILE"
