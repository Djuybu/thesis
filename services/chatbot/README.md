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
3. **LLM** — một trong **ba** provider (đổi trong `.env`, restart gateway):
   - **Ollama:** `LLM_PROVIDER=ollama` + `ollama pull qwen2.5:3b`
   - **OpenAI:** `LLM_PROVIDER=openai` + `OPENAI_API_KEY=sk-...` + `OPENAI_MODEL=gpt-4o-mini`
   - **Gemini:** `LLM_PROVIDER=gemini` + `GEMINI_API_KEY=AIza...` + `GEMINI_MODEL=gemini-2.0-flash`

   Hướng dẫn **so sánh 3 model cho báo cáo**: [MODEL-COMPARISON-VI.md](MODEL-COMPARISON-VI.md)

   Sau khi thêm Gemini lần đầu: `cd ../../tools/dremio-mcp && uv sync`
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

## Thời gian phản hồi & token (so sánh model)

| Cách | Mô tả |
|------|--------|
| **Terminal gateway** | `elapsed_ms=...`, `token_usage={input, output, total}`, `step_timings_ms`, `step_token_usage`. |
| **UI** | Dòng cuối tin nhắn: *⏱ 12.3s · tokens: 3650 (in 3200 / out 450) · …* (rebuild `dac/ui` nếu cần). |
| **API JSON** | `elapsed_ms`, `token_usage`, `step_token_usage`, `step_timings_ms` trong `/chat` và `/resume`. |
| **Báo cáo 3 model** | [MODEL-COMPARISON-VI.md](MODEL-COMPARISON-VI.md) — bảng mẫu thời gian + token. |

Biến môi trường: `AGENT_STEP_TIMINGS=0` (chỉ tổng thời gian); `AGENT_TOKEN_USAGE=0` (tắt token hoàn toàn).

### File log JSONL (phân tích một chỗ)

Mỗi lần **hỏi AI** (`/chat`) hoặc **resume** HITL, gateway **append một dòng JSON** vào:

`services/chatbot/logs/agent-runs.jsonl` (mặc định khi chạy `./run-gateway.sh`)

Mỗi dòng gồm: `timestamp`, `phase`, `llm_provider`, `model`, `user_message`, `status`, `answer`, `execution_result`, `elapsed_ms`, `step_timings_ms`, `token_usage`, `step_token_usage`, `proposed_sql`, `interrupt`, …

```bash
# Xem dòng mới nhất
tail -n 1 services/chatbot/logs/agent-runs.jsonl | python3 -m json.tool

# Lọc theo provider
jq -s 'map(select(.llm_provider=="openai"))' services/chatbot/logs/agent-runs.jsonl

# Cộng token theo thread (full HITL = 3 dòng cùng thread_id)
jq -s 'group_by(.thread_id) | map({thread: .[0].thread_id, ms: (map(.elapsed_ms) | add), tokens: (map(.token_usage.total_tokens // 0) | add)})' services/chatbot/logs/agent-runs.jsonl
```

Tắt: `AGENT_RUN_LOG=0`. Đổi đường dẫn: `AGENT_RUN_LOG_PATH=/path/to/file.jsonl`.

## Tệp trong thư mục

| File | Mục đích |
|------|----------|
| `run-mcp.sh` | Wrapper gọi `uv run dremio-mcp-server run` với `mcp-oss.yaml` |
| `run-gateway.sh` | Wrapper gọi `uv run dremio-sql-agent` với biến từ `.env` |
| `.env.example` | Mẫu biến môi trường (gateway + MCP + LLM) |
| `MODEL-COMPARISON-VI.md` | So sánh Ollama / OpenAI / Gemini cho báo cáo |
| `mcp-oss.yaml.example` | Mẫu cấu hình MCP (URI + PAT Dremio + chế độ tool) |

## Liên quan

- Code agent + gateway: [`tools/dremio-mcp/src/dremioai/`](../../tools/dremio-mcp/src/dremioai/)
- DAC reverse-proxy: [`dac/backend/src/main/java/com/dremio/dac/server/ChatbotGatewayProxyServlet.java`](../../dac/backend/src/main/java/com/dremio/dac/server/ChatbotGatewayProxyServlet.java)
- UI: [`dac/ui/src/components/AIChatbot/`](../../dac/ui/src/components/AIChatbot/)
- Hướng dẫn chi tiết bằng tiếng Việt: [`tools/dremio-mcp/docs/RUN-VI.md`](../../tools/dremio-mcp/docs/RUN-VI.md)
