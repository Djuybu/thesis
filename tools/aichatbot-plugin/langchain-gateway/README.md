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
| **Strict grounding** | `GATEWAY_STRICT_GROUNDING` (mặc định bật) + `strict_grounding` trong body: nối quy tắc chống bịa tên bảng/cột; khi bật strict và không gửi `temperature` thì mặc định dùng `0`. Xem `lc_grounding.py`. |
| **Data query workflow** | `GATEWAY_DATA_QUERY_WORKFLOW` (mặc định bật) + `data_query_workflow` trong body: hướng dẫn LLM **tìm bảng → đọc schema → hỏi xác nhận (“có đúng ý bạn không?”) → chạy SQL**. Là quy tắc prompt (ReAct); xác nhận cứng cần LangGraph `interrupt` — xem [LANGCHAIN-LANGGRAPH.md](LANGCHAIN-LANGGRAPH.md). |

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
export OLLAMA_MODEL=qwen3.5:9b
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
| `GATEWAY_STRICT_GROUNDING` | `true` / `1` (mặc định) — bật khối hướng dẫn gắn với dữ liệu (Dremio/PDF). `false` — tắt phần nối đó (giữ `GATEWAY_SYSTEM_PROMPT` + `user_context`). |
| `GATEWAY_DATA_QUERY_WORKFLOW` | `true` / `1` (mặc định) — nối quy trình câu hỏi dữ liệu Dremio (discover → schema → xác nhận user → SQL). `false` — tắt khối đó. |
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
| `GATEWAY_HITL_SQL_ENABLED` | `true` (mặc định) — bật `POST /gateway/hitl/sql/start` & `resume`. `false` — tắt (404). |

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
  "multi_agent": false,
  "strict_grounding": true,
  "data_query_workflow": true
}
```

Response mở rộng: `user_id`, `rag_tenant_id`, `intent` (`rag` \| `dremio` \| `both` khi multi-route), `multi_agent`, `strict_grounding`, `data_query_workflow`.

### `POST /gateway/hitl/sql/start` — SQL có duyệt người (StateGraph + `interrupt`)

**LangGraph** cố định: Discover → Schema → Đề xuất SQL → **dừng** chờ UI. Chỉ sau `POST /gateway/hitl/sql/resume` với `approved: true` mới gọi `RunSqlQuery`.

Header: `Authorization: Bearer <DREMIO_TOKEN>`

Body (rút gọn):

```json
{
  "message": "Top 10 khách hàng theo doanh thu tháng trước",
  "user_context": "{\"default_schema\":\"...\"}",
  "model": "qwen2.5:3b",
  "thread_id": null
}
```

- Trả về `status: "interrupted"` + `thread_id` + `interrupt` (payload có `proposed_sql`, `table_fqn`, …) → hiển thị cho user và nút Chấp thuận / Từ chối.
- Hoặc `status: "completed"` / `"error"` nếu lỗi trước bước duyệt.

### `POST /gateway/hitl/sql/resume`

```json
{
  "thread_id": "hitl-sql-…",
  "approved": true,
  "sql_override": "SELECT …"
}
```

- `approved: false` → không thực thi SQL.
- `sql_override` (tuỳ chọn): khi duyệt, chạy câu này thay cho bản đề xuất.

Chi tiết kiến trúc: [LANGCHAIN-LANGGRAPH.md](LANGCHAIN-LANGGRAPH.md) mục 7.

### Câu hỏi kiểu “tìm tài xế tháng vừa rồi” — LLM có biết bảng nào không?

- **Không “biết sẵn”** tên bảng vật lý trong Dremio chỉ từ câu chữ tiếng Việt. Model **không** nối từ “tài xế” tới `"catalog"."space"."drivers"` nếu chưa có thông tin từ **tool MCP** (tìm bảng/view, `INFORMATION_SCHEMA`, lấy schema, chạy SQL thử…) hoặc từ **`user_context`** / hội thoại trước.
- **Có thể tới một bảng cụ thể** nếu agent **gọi tool** khám phá catalog và chọn bảng phù hợp từ **kết quả thật** trả về, *hoặc* bạn cấp sẵn trong `user_context` (ví dụ: bảng khách hàng nghiệp vụ, khóa chính, cột ngày).
- **“Tháng vừa rồi”** cần map sang **cột ngày/giờ thực** trong schema (ví dụ `trip_date`); không có schema thì chỉ đoán mù phạm vi thời gian.
- Khi **strict grounding** bật, agent được yêu cầu **không** đặt tên bảng/cột chưa xuất hiện trong output tool hoặc `user_context`; nếu chưa khám phá được dataset đúng, nên **nói không chắc / cần làm rõ** thay vì bịa tên bảng — đó là hành vi mong muốn để tránh trả lời sai.

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
| `lc_grounding.py` | **Strict grounding** + **data query workflow** (xác nhận bảng trước SQL), `GATEWAY_STRICT_GROUNDING`, `GATEWAY_DATA_QUERY_WORKFLOW`. |
| `lc_hitl_sql.py` | **StateGraph** HITL: discover → schema → SQL → `interrupt` → (resume) `RunSqlQuery`. |

---

## English summary

The gateway uses **LangGraph** `create_react_agent`, **langchain-mcp-adapters** for Dremio MCP over HTTP, **RunnableWithMessageHistory** with optional **Redis**. **RAG** is optional: set `GATEWAY_RAG_DIR`, pull an embedding model in Ollama, upload PDFs, then the agent can call **`search_uploaded_documents`**. **Per-user keys** use `user_id` / `X-User-Id`. **Multi-route mode** (supervisor-style routing) picks RAG-heavy vs Dremio-heavy tool sets. **HTTP GET** tools are restricted by **`GATEWAY_HTTP_TOOL_ALLOWLIST`**. For persistent chat memory across restarts, configure **Redis**; RAG indices persist on disk under **`GATEWAY_RAG_DIR`**.

---

## Tham chiếu triển khai OSS đầy đủ

Xem [../DEPLOYMENT.md](../DEPLOYMENT.md) và [../README.md](../README.md).
