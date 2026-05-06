# LangChain & LangGraph trong dự án — tóm tắt hướng dẫn

Tài liệu này tóm tắt **cách LangChain và LangGraph được dùng** trong gateway (`langchain-gateway/`). Chi tiết cài đặt, biến môi trường và API đầy đủ nằm trong [README.md](README.md).

---

## 1. Vai trò trong kiến trúc

| Thành phần | Vai trò trong dự án |
|-------------|----------------------|
| **LangChain** | Chuẩn hoá **LLM** (`ChatOllama`), **tin nhắn** (`HumanMessage`, `AIMessage`), **tool** (`@tool`, MCP → LangChain tools), **runnable có lịch sử** (`RunnableWithMessageHistory`), **RAG** (FAISS, loaders, splitters trong `lc_rag.py`). |
| **LangGraph** | **ReAct:** `create_react_agent` (`/gateway/chat`). **StateGraph + `interrupt`:** luồng SQL có duyệt bắt buộc (`/gateway/hitl/sql/*`, `lc_hitl_sql.py`). |

Gateway là **FastAPI**: mỗi `POST /gateway/chat` dựng lại client MCP + danh sách tool + agent, rồi `ainvoke` với `recursion_limit` (mặc định 25).

---

## 2. Gói Python liên quan (tham chiếu)

Theo `requirements.txt` (phiên bản tối thiểu khai báo):

- `langchain-core`, `langchain-community`, `langchain-ollama`, `langchain-text-splitters`
- `langgraph` (prebuilt `create_react_agent`)
- `langchain-mcp-adapters` (`MultiServerMCPClient`)

---

## 3. LangGraph: agent ReAct

**Import:** `from langgraph.prebuilt import create_react_agent`

**Cách dùng:** `create_react_agent(llm, tools, prompt=system_prompt)` trong `gateway_app.py`.

**Ý nghĩa hành vi:**

- LLM quyết định **có** gọi tool hay không và **gọi tool nào** (MCP Dremio, RAG, HTTP GET).
- **Không** có pipeline cứng trong code: “bước 1 luôn tìm bảng → bước 2 luôn schema → bước 3 luôn SQL”. Thứ tự là **kết quả suy luận** của model + prompt (strict grounding + **data query workflow** nếu bật — xem mục 4.6).
- Giới hạn độ sâu vòng lặp: `recursion_limit` trong `config` khi `ainvoke`.

**Multi-route (tuỳ chọn):** trước khi tạo agent, `classify_intent` trong `lc_agents.py` có thể chọn tập tool **rag** / **dremio** / **both** — vẫn là **một** ReAct agent mỗi request, chỉ khác **danh sách tool** được gắn vào.

---

## 4. LangChain: các phần đang chạy

### 4.1 LLM

- `ChatOllama` (`lc_agents.build_llm`): model chat theo `OLLAMA_MODEL` / body `model`, nhiệt độ từ request hoặc `GATEWAY_TEMPERATURE`; với **strict grounding** bật và không gửi `temperature`, gateway dùng `0`.

### 4.2 MCP → Tool

- `MultiServerMCPClient` (`langchain_mcp_adapters`): server tên `dremio`, URL streamable HTTP qua plugin (`AICHAT_MCP_PROXY_URL`), header `Authorization` giống Dremio.
- `await mcp_client.get_tools(server_name="dremio")` đưa tool Dremio (metadata, SQL, …) vào agent như tool LangChain chuẩn.

### 4.3 Lịch sử hội thoại

- `RunnableWithMessageHistory` bọc agent: `input_messages_key` / `history_messages_key` = `"messages"`.
- Store: Redis nếu có `GATEWAY_REDIS_URL`, không thì in-memory (`lc_history.py`).
- Session: `session_id` / `X-Chat-Session-Id`, tùy `user_id` / `X-User-Id` để tách tenant và key history.

### 4.4 Tool tự viết (không qua MCP)

| Nguồn | Mô tả |
|--------|--------|
| `lc_rag.py` | `@tool` `search_uploaded_documents` — tra cứu chunk PDF đã index (FAISS + Ollama embeddings). |
| `lc_http_tools.py` | GET nội bộ theo allowlist (`GATEWAY_HTTP_TOOL_ALLOWLIST`). |

### 4.5 Prompt & grounding

- `compose_system_prompt` (`lc_agents.py`): nối `GATEWAY_SYSTEM_PROMPT` (hoặc default) với `user_context`.
- `append_strict_grounding` (`lc_grounding.py`): nếu bật (`GATEWAY_STRICT_GROUNDING` hoặc body `strict_grounding`), nối thêm quy tắc **chỉ tin metadata/SQL/PDF từ tool**, không bịa tên bảng/cột.

### 4.6 Quy trình câu hỏi dữ liệu Dremio (prompt — không phải graph riêng)

Khi bật **`GATEWAY_DATA_QUERY_WORKFLOW`** (mặc định) hoặc body `data_query_workflow: true`, system prompt được nối thêm hướng dẫn:

1. **Tìm bảng/view** phù hợp (MCP catalog/search).  
2. **Đọc schema** (cột, kiểu).  
3. **Hỏi xác nhận** người dùng: nêu rõ bảng dự định và hỏi có đúng ý không (có thể tiếng Việt).  
4. Chỉ sau đó (hoặc khi prompt cho phép bỏ qua: FQN đã có trong `user_context` / user đã đồng ý trong lượt trước) mới **`RunSqlQuery`**.

Đây là **hướng dẫn hành vi** cho LLM trong một ReAct agent; **không** dừng máy chủ để chờ nút bấm. Luồng xác nhận thực tế thường là **hai lượt chat**: lượt 1 đề xuất bảng + hỏi; lượt 2 user “đúng/ok” → agent chạy SQL. Model vẫn có thể vi phạm quy trình trong một lượt — để **bắt buộc** không gọi SQL trước khi duyệt, dùng **`/gateway/hitl/sql/*`** ([mục 7](#7-human-in-the-loop-sql-stategraph--interrupt)).

---

## 5. Chain (LCEL) vs Graph: nên dùng gì?

### LangChain “chain” (runnable / LCEL)

- **Ý nghĩa:** Nối các bước tuyến tính: `prompt | llm | parser`, hoặc vài bước cố định.  
- **Thuận tiện cho chatbot:** Tách nhỏ tác vụ (trích xuất tham số → sinh SQL từ template), dễ test, ít ma thuật.  
- **Hạn chế:** Vòng lặp “suy nghĩ — gọi tool — đọc kết quả — lặp” **không** gói gọn trong một chain tuyến tính đơn giản; vì vậy agent dùng **LangGraph ReAct** hoặc graph tùy chỉnh.

### LangGraph `create_react_agent` (đang dùng)

- **Ý nghĩa:** Một **đồ thị** có sẵn cho mô hình **ReAct** — LLM và tool luân phiên cho đến khi đủ.  
- **Thuận tiện:** Một API chat, nhiều tool (MCP, RAG, HTTP), ít code so với tự viết vòng lặp tool-calling.

### LangGraph `StateGraph` (hướng mở rộng)

- **Ý nghĩa:** Bạn định nghĩa **từng node** (vd. `discover`, `schema`, `human_approve`, `run_sql`) và cạnh chuyển trạng thái.  
- **Khi cần:** Xác nhận bắt buộc trước SQL, audit log từng bước, policy “chỉ SELECT sau khi duyệt”. Có thể dùng **human-in-the-loop** (`interrupt`) để server thật sự dừng cho đến khi client gửi tiếp.

### Tóm lại cho đề tài

- **LangChain** giúp chatbot **tổ chức** LLM + tool + bộ nhớ + RAG theo **module** và **runnable** có thể tái sử dụng.  
- **LangGraph** phù hợp khi cần **luồng có trạng thái** (agent lặp, hoặc sau này graph nhiều bước có xác nhận người dùng).  
- Song song, dự án có **`StateGraph` + `interrupt()`** cho luồng SQL có **dừng chờ duyệt** trước khi gọi `RunSqlQuery` — xem [mục 7](#7-human-in-the-loop-sql-stategraph--interrupt).

---

## 6. Luồng suy luận tóm tắt (một request chat)

1. Xác thực Bearer → tải tool MCP Dremio.
2. (Tuỳ chọn) Thêm RAG tool, HTTP tools.
3. (Tuỳ chọn) Phân loại intent → chọn subset tool.
4. Ghép system prompt + strict grounding + (tuỳ chọn) data query workflow.
5. `create_react_agent` + `RunnableWithMessageHistory`.
6. `ainvoke`: ReAct — model có thể gọi tool để **khám phá catalog**, **đọc schema**, **chạy SQL**, **tìm PDF**, v.v.; kết quả tool đưa vào ngữ cảnh; bước cuối là `AIMessage` → trả về `answer`.

---

## 7. Human-in-the-loop SQL (`StateGraph` + `interrupt`)

**Mã:** `lc_hitl_sql.py` + endpoint `POST /gateway/hitl/sql/start` và `POST /gateway/hitl/sql/resume` trong `gateway_app.py`.

**Luồng cố định (code, không chỉ prompt):**

1. `discover` — gọi tool MCP `SearchTableAndViews`.  
2. `pick_table` — LLM chọn một FQN từ kết quả (structured output).  
3. `fetch_schema` — `GetSchemaOfTable`.  
4. `propose_sql` — LLM sinh một câu SELECT (structured output).  
5. `human_gate` — `interrupt({...})`: đồ thị **dừng**, API trả `status: "interrupted"` + `thread_id` + payload (`proposed_sql`, `table_fqn`, …).  
6. Client gửi **`/gateway/hitl/sql/resume`** với cùng `thread_id` và `approved` / `sql_override`.  
7. Nếu duyệt — `execute_sql` (`RunSqlQuery`) → `finalize` (tóm tắt). Nếu từ chối — kết thúc với thông báo, **không** chạy SQL.

**Checkpointer:** `InMemorySaver` dùng chung trong process (key = `thread_id`). Restart gateway mất trạng thái tạm dừng — production nên đổi sang checkpointer bền (Postgres/Redis theo tài liệu LangGraph).

**Tắt HITL:** `GATEWAY_HITL_SQL_ENABLED=false`.

**Lưu ý:** `/gateway/chat` (ReAct) vẫn hoạt động như cũ; HITL là **luồng API riêng** cho kịch bản cần kiểm soát chặt.

---

## 8. Bản đồ file mã

| File | LangChain / LangGraph |
|------|------------------------|
| `gateway_app.py` | `create_react_agent`, `RunnableWithMessageHistory`, `MultiServerMCPClient`, `/gateway/chat`, **HITL SQL** endpoints. |
| `lc_hitl_sql.py` | `StateGraph` + `interrupt`, discover → schema → propose → duyệt → `RunSqlQuery`. |
| `lc_agents.py` | `ChatOllama`, `classify_intent`, `pick_tools_for_intent`, `compose_system_prompt`. |
| `lc_rag.py` | Document loaders, FAISS, embeddings, tool RAG. |
| `lc_http_tools.py` | Tool GET. |
| `lc_history.py` | History store cho runnable. |
| `lc_grounding.py` | Strict grounding + **data query workflow** (discover → schema → confirm → SQL). |
| `lc_config.py` | Env helper (không phụ thuộc LC trực tiếp). |

---

## 9. Điểm quan trọng cho luận văn / thiết kế

- **`/gateway/chat`:** LangGraph **prebuilt ReAct** — thứ tự tool do model quyết, có **prompt** workflow trong `lc_grounding.py`.
- **`/gateway/hitl/sql/*`:** **StateGraph** tùy chỉnh — thứ tự bước **cố định trong code**; **SQL chỉ chạy sau** `interrupt` + `Command(resume=…)` với `approved: true`.
- **Độ “đúng” với Dremio** vẫn phụ thuộc tool MCP + LLM chọn bảng/cột; HITL **không** loại trừ sai ngữ nghĩa nhưng **loại trừ thực thi không qua duyệt**.

---

## 10. Tham chiếu nhanh

- Hướng dẫn đầy đủ gateway: [README.md](README.md)
- Triển khai OSS (Dremio + plugin + MCP + Ollama): [../DEPLOYMENT.md](../DEPLOYMENT.md)
