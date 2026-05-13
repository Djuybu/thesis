# services/chatbot

Trạm điều phối khởi động cho **chatbot AI SQL** của Dremio. Code Python thực
thi vẫn nằm ở [`tools/dremio-mcp/`](../../tools/dremio-mcp/); thư mục này chỉ
chứa **script + biến môi trường + tài liệu** để Dremio và chatbot bắt tay
nhau ở local/dev.

## Sơ đồ kết nối

```
┌─────────────────────────┐     /aichat/v1/*       ┌────────────────────────────┐
│  Dremio UI (DAC, :9047) │ ───────────────────▶  │ DAC reverse-proxy servlet  │
│  dac/ui/.../AIChatbot   │                       │ (ChatbotGateway*Servlet)   │
└─────────────────────────┘                       └─────────────┬──────────────┘
                                                                │ services.coordinator.web
                                                                │ .chatbot.gateway.base_url
                                                                ▼
                                                  ┌────────────────────────────┐
                                                  │ dremio-sql-agent (FastAPI) │
                                                  │ :9292   (gateway/app.py)   │
                                                  └─────────────┬──────────────┘
                                                                │ DREMIO_MCP_URL
                                                                ▼
                                                  ┌────────────────────────────┐
                                                  │ dremio-mcp-server          │
                                                  │ :8080   (servers/mcp.py)   │
                                                  └─────────────┬──────────────┘
                                                                │ PAT (mcp-oss.yaml)
                                                                ▼
                                                  ┌────────────────────────────┐
                                                  │ Dremio coordinator REST    │
                                                  │ :9047                       │
                                                  └────────────────────────────┘

                                  Gateway → LLM (Ollama, :11434)
```

## Khởi động (local)

1. **Dremio đang chạy** ở `http://localhost:9047`.
2. **Cấu hình DAC trỏ tới gateway** — sửa [`conf/dremio.conf`](../../conf/dremio.conf) (key `services.coordinator.web.chatbot.gateway.base_url`, hoặc env `DREMIO_CHATBOT_GATEWAY_BASE_URL`) thành `http://127.0.0.1:9292`, rồi khởi động lại coordinator. Khoá cũ `services.coordinator.web.aichatbot.plugin.base_url` / env `DREMIO_AICHATBOT_PLUGIN_BASE_URL` vẫn được công nhận.
3. **Ollama**:
   ```bash
   ollama serve &
   ollama pull qwen2.5:3b
   ```
4. **MCP server** (terminal 1):
   ```bash
   cd services/chatbot
   cp mcp-oss.yaml.example mcp-oss.yaml   # rồi sửa PAT/URI Dremio
   ./run-mcp.sh
   ```
5. **SQL Agent gateway** (terminal 2):
   ```bash
   cd services/chatbot
   cp .env.example .env                    # rồi sửa nếu cần
   ./run-gateway.sh
   ```
6. **Kiểm tra**:
   ```bash
   curl http://127.0.0.1:9292/aichat/health
   ```
   rồi mở Dremio UI → nút **AI** chat.

## Tệp trong thư mục

| File | Mục đích |
|------|----------|
| `run-mcp.sh` | Wrapper gọi `uv run dremio-mcp-server run` với `mcp-oss.yaml` |
| `run-gateway.sh` | Wrapper gọi `uv run dremio-sql-agent` với biến từ `.env` |
| `.env.example` | Mẫu biến môi trường (gateway + MCP + Ollama) |
| `mcp-oss.yaml.example` | Mẫu cấu hình MCP (URI + PAT Dremio + chế độ tool) |

## Liên quan

- Code agent + gateway: [`tools/dremio-mcp/src/dremioai/`](../../tools/dremio-mcp/src/dremioai/)
- DAC reverse-proxy: [`dac/backend/src/main/java/com/dremio/dac/server/ChatbotGatewayProxyServlet.java`](../../dac/backend/src/main/java/com/dremio/dac/server/ChatbotGatewayProxyServlet.java)
- UI: [`dac/ui/src/components/AIChatbot/`](../../dac/ui/src/components/AIChatbot/)
- Hướng dẫn chi tiết bằng tiếng Việt: [`tools/dremio-mcp/docs/RUN-VI.md`](../../tools/dremio-mcp/docs/RUN-VI.md)
