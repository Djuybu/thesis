# LangChain Gateway — hướng dẫn đầy đủ

Gateway FastAPI (`gateway_app.py`) kết hợp **LangGraph ReAct agent**, **Ollama**, **Dremio MCP** (qua plugin `aichatbot` + `dremio-mcp`), và các mở rộng **LangChain** sau đây.

---

## Đã bổ sung (LangChain / LangGraph)

| Khả năng | Mô tả trong gateway |
|----------|---------------------|
| **Chat với PDF (RAG)** | Đánh chỉ mục PDF (FAISS + embedding Ollama), tool `search_uploaded_documents`. Bật bằng `GATEWAY_RAG_DIR`; upload qua `POST /gateway/rag/upload`. |
| **Memory theo user** | Gửi `user_id` (JSON) hoặc header `X-User-Id`; history key = `user:{id}:session:{session}`. RAG tenant = `user:{id}` khi có user. |
| **Multi-turn** | `RunnableWithMessageHistory` — giữ ngữ cảnh theo `history_key` ở trên. |
| **Agent gọi API** | Tool `http_get_allowlisted` — chỉ URL có tiền tố trong `GATEWAY_HTTP_TOOL_ALLOWLIST`. |
| **Multi-tool** | MCP (Dremio) + RAG + HTTP tools trong một agent (hoặc tập con khi bật multi-route). |
| **MCP + LangChain** | `langchain-mcp-adapters` `MultiServerMCPClient` → streamable HTTP qua `/aichat/mcp-proxy`. |
| **Multi-route agents** | `GATEWAY_MULTI_AGENT=true` hoặc `"multi_agent": true`: LLM phân loại ý định **rag / dremio / both**, rồi chọn bộ tool tương ứng (hai “chuyên gia” ReAct, không phải graph lồng nhau phức tạp). |
| **Context-aware** | Trường `user_context` trong body chat — ghép vào system prompt (ví dụ JSON profile từ Dremio). |
| **Persistent memory** | **`GATEWAY_REDIS_URL`**: lịch sử chat bền. Không có Redis → in-memory (mất khi restart). Chỉ mục RAG lưu trên đĩa dưới `GATEWAY_RAG_DIR`. |

---

## Cài đặt

```bash
cd tools/aichatbot-plugin/langchain-gateway
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

**Ollama**

- Model chat (ví dụ): `ollama pull qwen2.5:3b`
- Model embedding cho RAG (khuyến nghị): `ollama pull nomic-embed-text`

---

## Chạy gateway

```bash
export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_MODEL=qwen2.5:3b
export AICHAT_MCP_PROXY_URL=http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp
# Tuỳ chọn — lịch sử bền:
# export GATEWAY_REDIS_URL=redis://127.0.0.1:6379/0
# Tuỳ chọn — RAG PDF:
# export GATEWAY_RAG_DIR=/var/tmp/aichat-rag
# export GATEWAY_EMBED_MODEL=nomic-embed-text
python -m uvicorn gateway_app:app --host 127.0.0.1 --port 9292
```

Script PowerShell: `scripts/run-gateway.ps1` (nếu có trong repo).

---

## Biến môi trường chính

| Biến | Ý nghĩa |
|------|---------|
| `OLLAMA_BASE_URL` | Endpoint Ollama (mặc định `http://127.0.0.1:11434`). |
| `OLLAMA_MODEL` | Model chat. |
| `GATEWAY_TEMPERATURE` | Nhiệt độ mặc định (có thể ghi đè bằng `temperature` trong request). |
| `AICHAT_MCP_PROXY_URL` | URL MCP qua plugin (Bearer giống Dremio). |
| `GATEWAY_SYSTEM_PROMPT` | System prompt mặc định. |
| `GATEWAY_REDIS_URL` | Bật Redis cho message history. |
| `GATEWAY_HISTORY_KEY_PREFIX` | Tiền tố key Redis (mặc định `aichat:`). |
| `GATEWAY_HISTORY_TTL_SECONDS` | TTL history (0 = không hết hạn). |
| `GATEWAY_RAG_DIR` | Thư mục lưu FAISS; **không set** → tắt RAG. |
| `GATEWAY_EMBED_MODEL` | Model Ollama embedding (mặc định `nomic-embed-text`). |
| `GATEWAY_MULTI_AGENT` | `true` / `1` — bật phân luồng RAG vs Dremio khi đã có chỉ mục PDF. |
| `GATEWAY_ROUTER_MODEL` | Model riêng cho bước phân loại (mặc định dùng chung model chat). |
| `GATEWAY_HTTP_TOOL_ALLOWLIST` | Danh sách tiền tố URL, phân tách bằng dấu phẩy — cho phép tool GET nội bộ. |
| `GATEWAY_HTTP_TOOL_TIMEOUT_SECONDS` | Timeout HTTP tool. |
| `AICHAT_MCP_TIMEOUT_SECONDS` | Timeout MCP client. |
| `AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS` | Timeout đọc SSE MCP. |

---

## API

### `POST /gateway/chat`

Header: `Authorization: Bearer <DREMIO_TOKEN>`  
Tuỳ chọn: `X-Chat-Session-Id`, `X-User-Id`

Body JSON (rút gọn):

```json
{
  "message": "Liệt kê các space trong catalog",
  "session_id": "sess-1",
  "user_id": "alice",
  "user_context": "{\"role\":\"analyst\"}",
  "model": "qwen2.5:3b",
  "temperature": 0,
  "multi_agent": false
}
```

Response mở rộng: `user_id`, `rag_tenant_id`, `intent` (`rag` \| `dremio` \| `both` khi multi-route), `multi_agent`.

### RAG

- `POST /gateway/rag/upload` — `multipart/form-data`: field `file` (`.pdf`), tuỳ chọn `session_id`, `user_id` (form).
- `GET /gateway/rag/status` — query `session_id`, `user_id` hoặc header tương ứng.
- `DELETE /gateway/rag/clear` — xoá chỉ mục của tenant hiện tại.

Tenant RAG = `user:{user_id}` nếu có `user_id`, ngược lại = `session_id`.

---

## Kiến trúc mã (module)

| File | Vai trò |
|------|---------|
| `gateway_app.py` | FastAPI, `/gateway/chat`, endpoint RAG, nối MCP + agent. |
| `lc_config.py` | Đọc biến môi trường, chuẩn hoá Bearer. |
| `lc_history.py` | `resolve_tenant`, Redis/in-memory history store. |
| `lc_rag.py` | PDF → FAISS, tool tìm kiếm, `default_rag_manager()`. |
| `lc_http_tools.py` | Tool GET allowlist. |
| `lc_agents.py` | LLM, system prompt + `user_context`, phân loại intent, chọn tool theo intent. |

---

## English summary

The gateway uses **LangGraph** `create_react_agent`, **langchain-mcp-adapters** for Dremio MCP over HTTP, **RunnableWithMessageHistory** with optional **Redis**. **RAG** is optional: set `GATEWAY_RAG_DIR`, pull an embedding model in Ollama, upload PDFs, then the agent can call **`search_uploaded_documents`**. **Per-user keys** use `user_id` / `X-User-Id`. **Multi-route mode** (supervisor-style routing) picks RAG-heavy vs Dremio-heavy tool sets. **HTTP GET** tools are restricted by **`GATEWAY_HTTP_TOOL_ALLOWLIST`**. For persistent chat memory across restarts, configure **Redis**; RAG indices persist on disk under **`GATEWAY_RAG_DIR`**.

---

## Tham chiếu triển khai OSS đầy đủ

Xem [../DEPLOYMENT.md](../DEPLOYMENT.md) và [../README.md](../README.md).
