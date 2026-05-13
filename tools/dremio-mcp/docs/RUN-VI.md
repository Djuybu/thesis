# Chạy chat AI (Dremio + MCP + SQL Agent) — hướng dẫn ngắn

> **Cách nhanh nhất**: dùng wrapper trong [`services/chatbot/`](../../../services/chatbot/README.md)
> (`run-mcp.sh` + `run-gateway.sh`). Tài liệu dưới đây giải thích cùng các
> bước nhưng gọi `uv` trực tiếp khi bạn muốn kiểm soát flag.

## Có cần Java endpoint (plugin JAR) không?

**Không.** Stack mới chỉ gồm **gateway Python** (`tools/dremio-mcp/src/dremioai/gateway/`)
và **MCP server Python** (`tools/dremio-mcp/src/dremioai/servers/mcp.py`):

- Plugin JAR cũ ở `tools/aichatbot-plugin/` đã bị xoá — nếu cần rollback, lấy lại từ git history.
- Dremio UI gọi `/aichat/*` trên cùng host; DAC **reverse proxy** tới URL trong `dremio.conf` → mặc định gateway Python **`dremio-sql-agent`** (cổng **9292**).
- Cần **Python 3.11+** (xem [deployment.md](deployment.md) nếu lỗi `beeai-framework` trên 3.10).

## Thứ tự chạy (local)

1. **Dremio** đang chạy (ví dụ `https://localhost:9047` hoặc tương đương).

2. **Cấu hình Dremio** — `base_url` trỏ tới gateway (cổng 9292):

   `services.coordinator.web.chatbot.gateway.base_url: "http://127.0.0.1:9292"`

   (Trong dev: `conf/dremio.conf` hoặc biến `DREMIO_CHATBOT_GATEWAY_BASE_URL`.)  
   Khoá cũ `services.coordinator.web.aichatbot.plugin.base_url` và env
   `DREMIO_AICHATBOT_PLUGIN_BASE_URL` vẫn được công nhận để khỏi phá deployment cũ.  
   Khởi động lại coordinator nếu vừa sửa.

3. **Ollama** (LLM):

   ```bash
   ollama serve
   ollama pull qwen2.5:3b   # hoặc model bạn dùng (3.5/4b ở chế độ "thinking" có thể timeout với CPU)
   ```

4. **Dremio MCP server** (HTTP, để agent gọi tool):

   ```bash
   # Cách gọn (qua wrapper):
   services/chatbot/run-mcp.sh

   # Hoặc gọi trực tiếp:
   cd tools/dremio-mcp
   uv venv && source .venv/bin/activate   # hoặc venv Python 3.11+
   uv sync   # hoặc: pip install -e .
   uv run dremio-mcp-server run -c local/mcp-oss.yaml --enable-streaming-http --port 8080
   ```

   Nếu khởi động MCP báo **`Permission denied`** khi ghi log vào `~/.local/share/dremioai/logs/`, thêm **`--no-log-to-file`** (wrapper đã bật sẵn).

   SQL Agent (`9292`) báo **`ConnectError`** / **`Failed to load MCP tools`** khi **không có** tiến trình MCP đang lắng nghe **`8080`** — giữ terminal bước 4 chạy song song với gateway.

   Tạo/sửa `local/mcp-oss.yaml` (hoặc `services/chatbot/mcp-oss.yaml` nếu dùng wrapper) cho đúng URI Dremio và PAT. **SQL agent** cần công cụ `SearchTableAndViews` → trong YAML thêm **`dremio.enable_search: true`**, rồi khởi động lại `dremio-mcp-server` (không bật thì agent báo *SearchTableAndViews not available*). Trên **Dremio OSS**, công cụ đó dùng **`GET /api/v3/catalog/search`** (không có `POST /api/v3/search` của bản enterprise); mã trong `dremio-mcp` đã tự chọn API phù hợp.

5. **SQL Agent gateway** (terminal khác):

   ```bash
   # Cách gọn (qua wrapper, đọc services/chatbot/.env):
   services/chatbot/run-gateway.sh

   # Hoặc gọi trực tiếp:
   cd tools/dremio-mcp
   source .venv/bin/activate
   export DREMIO_MCP_URL=http://127.0.0.1:8080/mcp/
   export OLLAMA_BASE_URL=http://127.0.0.1:11434
   export OLLAMA_MODEL=qwen2.5:3b
   # Mặc định tắt streaming Ollama (tránh treo với Qwen/…). Bật: OLLAMA_STREAM=1
   uv run dremio-sql-agent
   ```

   Một lần gọi `/aichat/ask` hay `/aichat/v1/chat` có thể **chạy rất lâu** (LangGraph + LLM + MCP + HITL); `curl` không in gì cho đến khi xong. Xem log gateway (`Agent chat_start start …`). Tùy chọn: `export AGENT_REQUEST_TIMEOUT_SECONDS=600` (giây) để nhận **504** thay vì treo vô hạn khi vượt ngưỡng.

   Log **quy trình agent** (bước graph, route, MCP tool): mặc định bật các dòng `[agent] step=…`. Tắt: `export AGENT_TRACE_LOG=0`.

6. **Kiểm tra:**

   ```bash
   curl http://127.0.0.1:9292/aichat/health
   ```

7. Mở **Dremio UI** → đăng nhập → nút **AI** chat.

## Dev UI riêng (webpack port 3005)

```bash
cd dac/ui
export DEV_PROXY_CONFIG_PATH=./build-utils/dev-proxy.aichatbot.example.js
npm run start
```

Proxy mẫu trỏ `/aichat` → `http://127.0.0.1:9292`.

## Tham chiếu thêm

- API gateway: [api-spec.md](api-spec.md)
- Triển khai / rollback: [deployment.md](deployment.md)
- Build monorepo: [BUILD-FULL-VI.md](../../../BUILD-FULL-VI.md)
