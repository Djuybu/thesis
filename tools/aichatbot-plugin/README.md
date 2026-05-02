# AI Chat Bot Plugin (standalone) — Dremio OSS + **local LLM**

Plugin chạy **tách biệt** khỏi DAC/OSS core: xác thực bằng token Dremio (`/apiv2/login`), lấy ngữ cảnh user (`/apiv2/user/{username}`), rồi gọi **LLM chạy trên máy bạn** (khuyến nghị API tương thích OpenAI: **Ollama**, **LM Studio**, **vLLM**, …).

---

## Mục lục

1. [Kiến trúc](#kiến-trúc)
2. [Tích hợp Dremio MCP](#tích-hợp-dremio-mcp)
3. [Chức năng](#chức-năng)
4. [Cài và chạy local LLM (quan trọng)](#cài-và-chạy-local-llm-quan-trọng)
5. [Build & chạy plugin](#build--chạy-plugin)
6. [Biến môi trường](#biến-môi-trường)
7. [API](#api)
8. [Giao diện web tối thiểu](#giao-diện-web-tối-thiểu)
9. [Bảo mật & lưu ý](#bảo-mật--lưu-ý)
10. [Xử lý sự cố](#xử-lý-sự-cố)
11. [English summary](#english-summary)
12. [**Triển khai đầy đủ (OSS + MCP + LangChain)**](DEPLOYMENT.md)
13. [**Gateway LangChain — chi tiết (RAG PDF, memory theo user, multi-route, …)**](langchain-gateway/README.md)

---

## Kiến trúc

```text
Browser / App
    →  POST /aichat/ask  (Authorization: Bearer <DREMIO_TOKEN>)
Plugin
    →  GET  Dremio /apiv2/login        (kiểm tra token)
    →  GET  Dremio /apiv2/user/{user}  (ngữ cảnh user, nếu có username)
    →  POST LLM /v1/chat/completions   (OpenAI-compatible, thường là Ollama/LM Studio local)
```

- **`AI_LLM_MODE=openai`** (mặc định): plugin tự dựng JSON chuẩn Chat Completions (`messages`, `model`, `stream:false`, …).
- **`AI_LLM_MODE=custom`**: plugin gửi payload “cầu nối” cũ (`prompt`, `model`, `dremioUserName`, `dremioUserContext`) cho gateway tự viết.

Tuỳ chọn, [Dremio MCP server](https://github.com/dremio/dremio-mcp) chạy HTTP có thể đứng **giữa** client và Dremio cho **MCP tools**; plugin đóng vai **cổng xác thực** + proxy (xem mục [Tích hợp Dremio MCP](#tích-hợp-dremio-mcp)).

---

## Tích hợp Dremio MCP

Repo [dremio/dremio-mcp](https://github.com/dremio/dremio-mcp) triển khai **Model Context Protocol** cho Dremio (khám phá catalog, chạy SQL, v.v.). Plugin `aichatbot` **không** thay thế MCP server; nó bổ sung:

1. **Xác thực giống UI OSS:** kiểm tra `Authorization: Bearer …` với `GET /apiv2/login` trên Dremio OSS của bạn.
2. **Proxy HTTP tới MCP:** sau khi token hợp lệ, forward request tới dremio-mcp đang chạy **streaming HTTP** (cùng header `Authorization` để MCP/OAuth verify như trong tài liệu dremio-mcp).

```text
Browser / SPA
  →  GET|POST /aichat/mcp-proxy?path=/mcp   (Authorization: Bearer <DREMIO_TOKEN>)
Plugin
  →  GET  Dremio /apiv2/login               (chỉ cho phép nếu token OK)
  →  GET|POST {DREMIO_MCP_HTTP_BASE}{path}  (forward body + Accept/Content-Type)
dremio-mcp
  →  Dremio (REST / Flight / … theo cấu hình MCP)
```

### Cách chạy nhanh (dev)

1. Clone [dremio-mcp](https://github.com/dremio/dremio-mcp), cài `uv`, tạo config Dremio (PAT hoặc OAuth) theo README upstream (`dremio-mcp-server config create dremioai …`).
2. Khởi động MCP ở chế độ HTTP (tham số chính xác xem `dremio-mcp-server run --help`; thường có `--enable-streaming-http` và `--port`). Ghi nhận **URL gốc** và **đường dẫn** endpoint MCP (thường dạng `/mcp` — có thể khác theo phiên bản).
3. Chạy plugin với biến môi trường (PowerShell ví dụ):

   ```powershell
   $env:DREMIO_BASE_URL="http://localhost:9047"
   $env:DREMIO_MCP_HTTP_BASE="http://127.0.0.1:8000"
   $env:DREMIO_MCP_HTTP_PATH="/mcp"
   java -jar tools\aichatbot-plugin\target\dremio-aichatbot-plugin-*.jar
   ```

4. Gọi proxy (body là payload MCP streamable HTTP của bạn):

   ```bash
   curl -s -X POST "http://localhost:9191/aichat/mcp-proxy?path=/mcp" \
     -H "Authorization: Bearer <DREMIO_TOKEN>" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
   ```

**Ghi chú:** Định dạng JSON-RPC / SSE tuân theo [MCP](https://modelcontextprotocol.io/) và phiên bản dremio-mcp bạn cài — plugin **chỉ relay** byte/string, không parse MCP.

### Hai kênh song song

| Kênh | Mục đích |
|------|-----------|
| `/aichat/ask` | Chat với **local LLM** (Ollama/LM Studio) qua OpenAI-compatible API. |
| `/aichat/mcp-proxy` | Gọi **dremio-mcp** (HTTP) để dùng MCP tools với cùng token Dremio đã kiểm tra. |

### Gateway LangChain (LLM + MCP + mở rộng LangChain)

Thư mục **`langchain-gateway/`**: FastAPI + LangGraph **`create_react_agent`** + **Ollama** + MCP qua **`/aichat/mcp-proxy`** (Bearer token Dremio). Khác [CLI LangChain trong dremio-mcp](https://github.com/dremio/dremio-mcp/blob/main/src/dremioai/servers/frameworks/langchain/server.py) (stdio), gateway OSS dùng **streamable HTTP** qua plugin.

**Đã bổ sung trong gateway (LangChain / LangGraph):**

- **RAG PDF**: chỉ mục file `.pdf` (FAISS + embedding Ollama), tool tìm trong tài liệu — bật bằng `GATEWAY_RAG_DIR`, upload `POST /gateway/rag/upload`.
- **Memory theo user / session**: `user_id` hoặc `X-User-Id` + `session_id` / `X-Chat-Session-Id`; history Redis tuỳ chọn (`GATEWAY_REDIS_URL`).
- **Multi-turn**: `RunnableWithMessageHistory`.
- **Agent gọi API nội bộ**: tool HTTP GET có **allowlist** (`GATEWAY_HTTP_TOOL_ALLOWLIST`).
- **Multi-tool**: MCP Dremio + (tuỳ chọn) RAG + HTTP.
- **Multi-route agents** (giám sát ý định): `GATEWAY_MULTI_AGENT` hoặc `"multi_agent": true` — phân luồng bộ tool RAG vs Dremio vs kết hợp.
- **Context-aware**: trường `user_context` trong body chat ghép vào system prompt.
- **Persistent memory**: Redis cho hội thoại; chỉ mục RAG trên đĩa dưới `GATEWAY_RAG_DIR`.

**Hướng dẫn đầy đủ (API, biến môi trường, kiến trúc module):** **[langchain-gateway/README.md](langchain-gateway/README.md)**.

**Triển khai từng bước (OSS + MCP + plugin + Ollama + gateway):** **[DEPLOYMENT.md](DEPLOYMENT.md)**.

Script (trong `langchain-gateway/scripts/`): `setup-venv.ps1`, `pull-qwen-ollama.ps1`, `run-gateway.ps1`, `run-dremio-mcp-http.ps1`.

---

## Chức năng

| Tính năng | Mô tả |
|-----------|--------|
| Xác thực Dremio | Bắt buộc header `Authorization` giống gọi API Dremio. |
| Ngữ cảnh user | Sau login, lấy `userName` từ JSON login rồi gọi `/apiv2/user/{userName}`; ghép vào system prompt (mode OpenAI). |
| **Local LLM** | Trỏ `AI_BACKEND_URL` tới Ollama/LM Studio (OpenAI-compatible). |
| Model | Mặc định `AI_MODEL_DEFAULT` (mặc định code: `llama3.2`); request có thể gửi thêm `"model":"..."`. |
| Nhiệt độ / token | Body JSON hỗ trợ tùy chọn `temperature`, `max_tokens` (chuyển thẳng cho OpenAI-compatible server). |
| System prompt | `AI_SYSTEM_PROMPT`; request có thể gửi `"system":"..."` để ghi đè. |
| CORS | `Access-Control-Allow-Origin: *` cho gọi từ trình duyệt (chỉ dùng tin cậy trong mạng nội bộ). |
| UI demo | `GET /aichat/chat.html` — form nhập token + prompt. |
| Cấu hình public | `GET /aichat/config` — không lộ secret, chỉ mô tả cấu hình. |
| Timeout LLM | `AI_REQUEST_TIMEOUT_SECONDS` (mặc định 120) — model local thường chậm hơn cloud. |
| Trả lời gọn | `AI_UNWRAP_OPENAI_CONTENT=true` (mặc định): trả JSON có `answer` + `llmRaw` (chuỗi JSON LLM đã escape). |
| **Proxy tới [dremio-mcp](https://github.com/dremio/dremio-mcp)** | `GET`/`POST` `/aichat/mcp-proxy` sau khi auth Dremio; cần `DREMIO_MCP_HTTP_BASE`. |
| **Gateway LangChain** (thư mục `langchain-gateway`) | `POST /gateway/chat` — Ollama + MCP qua `mcp-proxy`; tuỳ chọn **RAG PDF**, memory Redis, **user_id**, **user_context**, HTTP tool allowlist, **multi-route** (`GATEWAY_MULTI_AGENT`). Chi tiết: [langchain-gateway/README.md](langchain-gateway/README.md). |

---

## Cài và chạy local LLM (quan trọng)

### Ollama (khuyến nghị, đơn giản)

1. Cài [Ollama](https://ollama.com/) trên cùng máy hoặc máy trong LAN.
2. Kéo model (ví dụ):

   ```bash
   ollama pull llama3.2
   ```

3. Ollama mở API tương thích OpenAI tại:

   `http://127.0.0.1:11434/v1/chat/completions`

4. Chạy plugin với (PowerShell):

   ```powershell
   $env:DREMIO_BASE_URL="http://localhost:9047"
   $env:AICHAT_PORT="9191"
   $env:AI_BACKEND_URL="http://127.0.0.1:11434/v1/chat/completions"
   $env:AI_MODEL_DEFAULT="llama3.2"
   # Ollama local thường không cần API key — không set AI_BACKEND_AUTH
   java -jar tools\aichatbot-plugin\target\dremio-aichatbot-plugin-*.jar
   ```

**Lưu ý:** Dremio chạy trong Docker còn Ollama trên Windows host: dùng IP host thay vì `127.0.0.1` từ góc nhìn container (ví dụ `host.docker.internal` trên Docker Desktop), hoặc chạy plugin trên host để `127.0.0.1` tới Ollama được.

### LM Studio

1. Mở LM Studio → tab **Server** → bật server cục bộ (cổng thường `1234`).
2. Endpoint thường dùng:

   `http://127.0.0.1:1234/v1/chat/completions`

3. Gán:

   ```powershell
   $env:AI_BACKEND_URL="http://127.0.0.1:1234/v1/chat/completions"
   $env:AI_MODEL_DEFAULT="tên-model-trong-ui-lmstudio"
   ```

### vLLM / llama.cpp server / khác

Bất kỳ server nào hỗ trợ **`POST /v1/chat/completions`** với JSON giống OpenAI (`model`, `messages`, `stream`) đều có thể dùng với `AI_LLM_MODE=openai` (mặc định).

---

## Build & chạy plugin

```powershell
cd d:\path\to\dremio-oss
.\mvnw.cmd -pl tools/aichatbot-plugin -DskipTests package
```

```powershell
$env:DREMIO_BASE_URL="http://localhost:9047"
$env:AICHAT_PORT="9191"
$env:AI_BACKEND_URL="http://127.0.0.1:11434/v1/chat/completions"
$env:AI_MODEL_DEFAULT="llama3.2"
$env:AI_REQUEST_TIMEOUT_SECONDS="180"

java -jar tools\aichatbot-plugin\target\dremio-aichatbot-plugin-*.jar
```

- Web demo: **http://localhost:9191/aichat/chat.html**
- Kiểm tra nhanh: **http://localhost:9191/health**

---

## Biến môi trường

| Biến | Ý nghĩa | Mặc định |
|------|---------|----------|
| `DREMIO_BASE_URL` | URL Dremio (REST) | `http://localhost:9047` |
| `AICHAT_PORT` | Cổng plugin | `9191` |
| `AI_BACKEND_URL` | URL LLM (OpenAI-compatible hoặc custom gateway) | *(trống → mock sau khi auth)* |
| `AI_LLM_MODE` | `openai` \| `custom` | `openai` nếu có URL backend |
| `AI_MODEL_DEFAULT` | Model local/cloud | `llama3.2` |
| `AI_SYSTEM_PROMPT` | System prompt cho assistant | Chuỗi mặc định trong code |
| `AI_REQUEST_TIMEOUT_SECONDS` | Timeout HTTP tới LLM | `120` |
| `AI_UNWRAP_OPENAI_CONTENT` | `true`: bọc `{ answer, llmRaw }`; `false`: trả nguyên body LLM | `true` |
| `AI_BACKEND_AUTH` | API key gửi tới LLM (nếu server yêu cầu) | *(trống)* |
| `AI_BACKEND_AUTH_HEADER` | Tên header (ví dụ `Authorization`) | `Authorization` |
| `AI_BACKEND_AUTH_PREFIX` | Tiền tố giá trị (ví dụ `Bearer `) | `Bearer ` |
| `DREMIO_MCP_HTTP_BASE` | URL gốc [dremio-mcp](https://github.com/dremio/dremio-mcp) (HTTP / `--enable-streaming-http`), **không** có đường path MCP | *(trống → tắt `/aichat/mcp-proxy`)* |
| `DREMIO_MCP_HTTP_PATH` | Path mặc định khi client không gửi `?path=` | `/mcp` |
| `DREMIO_MCP_PROXY_TIMEOUT_SECONDS` | Timeout proxy tới MCP | `300` |

---

## API

### `GET /health`

Trạng thái service.

### `GET /aichat/config`

JSON: `dremioBaseUrl`, `llmMode`, `aiBackendConfigured`, `dremioMcpHttpConfigured`, `dremioMcpDefaultPath`, `defaultModel`, `requestTimeoutSeconds`, `unwrapOpenAiContent`, `mcpProxyTimeoutSeconds`.

### `GET /aichat/context`

- Header: **`Authorization: Bearer <token>`**
- Tùy chọn: **`X-Dremio-Username`** — trả profile từ `/apiv2/user/{username}`.
- Không có header username: chỉ kiểm tra token qua `/apiv2/login`.

### `POST /aichat/ask`

- Header: **`Authorization: Bearer <token>`**

- Body JSON tối thiểu: `{ "prompt": "..." }`
- Tùy chọn: `"model"`, `"system"`, `"temperature"`, `"max_tokens"`

Ví dụ `curl`:

```bash
curl -s -X POST http://localhost:9191/aichat/ask \
  -H "Authorization: Bearer <DREMIO_TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"Giải thích lakehouse trong 3 câu\",\"model\":\"llama3.2\",\"temperature\":0.7}"
```

### `GET` / `POST` `/aichat/mcp-proxy`

- Cần cấu hình **`DREMIO_MCP_HTTP_BASE`**.
- Header: **`Authorization: Bearer <token Dremio>`** (plugin kiểm tra với Dremio trước khi forward).
- Query tùy chọn: **`path`** — đường dẫn trên MCP server (mặc định `DREMIO_MCP_HTTP_PATH`). Chỉ cho phép ký tự an toàn (không có `..`).
- **GET:** forward GET tới `{DREMIO_MCP_HTTP_BASE}{path}`.
- **POST:** forward body + `Content-Type` / `Accept` nhận từ client.
- Phản hồi: status + body + `Content-Type` từ upstream (JSON hoặc `text/event-stream` nếu MCP stream).

---

## Giao diện web tối thiểu

Mở **http://&lt;AICHAT_HOST&gt;:&lt;port&gt;/aichat/chat.html** — nhập URL plugin, token Dremio, model local, prompt → gửi `POST /aichat/ask`.

---

## Bảo mật & lưu ý

- **Token Dremio** có quyền như user đó; không log token, không commit token vào git.
- Plugin **không thay thế** kiểm soát truy cập dữ liệu Dremio: chỉ forward token tới API Dremio; LLM chỉ nhận nội dung bạn đưa (system + user + đoạn profile JSON).
- CORS `*` chỉ phù hợp **mạng tin cậy** / lab. Production nên đặt reverse proxy (nginx) và thu hẹp CORS, TLS, rate limit.
- Local LLM: dữ liệu không gửi ra cloud nếu endpoint trỏ tới máy bạn — kiểm tra kỹ `AI_BACKEND_URL`.
- **MCP proxy:** token được gửi tới cả Dremio OSS và dremio-mcp; hạn chế CORS, TLS và phạm vi mạng như mọi API nhạy cảm.

---

## Xử lý sự cố

| Hiện tượng | Gợi ý |
|------------|--------|
| `401` từ plugin | Token Dremio sai/hết hạn; thử `GET /aichat/context` trước. |
| Timeout LLM | Tăng `AI_REQUEST_TIMEOUT_SECONDS`; kiểm tra GPU/CPU; model nhỏ hơn. |
| Connection refused tới `127.0.0.1:11434` | Ollama chưa chạy hoặc plugin chạy trong Docker không thấy localhost host. |
| JSON lỗi / `answer` trống | Đặt `AI_UNWRAP_OPENAI_CONTENT=false` để xem raw response LLM; kiểm tra server có đúng OpenAI Chat API không. |
| Model not found | `ollama pull <tên>` hoặc đổi `AI_MODEL_DEFAULT` / field `model` trong request. |
| `502` từ `/aichat/mcp-proxy` | MCP chưa chạy, sai `DREMIO_MCP_HTTP_BASE`, hoặc sai `path` (so với endpoint streamable HTTP thực tế). |
| Gateway `502` / “Failed to load MCP tools” | Token sai; plugin chưa bật `DREMIO_MCP_HTTP_BASE`; dremio-mcp chưa `--enable-streaming-http`; sai `AICHAT_MCP_PROXY_URL`. |
| Gateway `500` / Ollama | Chạy `ollama serve`, đã `ollama pull` đúng `OLLAMA_MODEL`. |

---

## English summary

- **Standalone** JAR: validates **Dremio** bearer token via `/apiv2/login`, optionally loads user JSON via `/apiv2/user/{userName}`, then calls your **OpenAI-compatible** endpoint (default), ideal for **Ollama** / **LM Studio** on `localhost`.
- Set `AI_BACKEND_URL` to e.g. `http://127.0.0.1:11434/v1/chat/completions`, `AI_MODEL_DEFAULT=llama3.2`, tune `AI_REQUEST_TIMEOUT_SECONDS` for slow local inference.
- **`AI_LLM_MODE=custom`** keeps the older JSON bridge for a self-hosted gateway.
- **[dremio-mcp](https://github.com/dremio/dremio-mcp)** (streaming HTTP): set `DREMIO_MCP_HTTP_BASE` and use **`GET`/`POST /aichat/mcp-proxy?path=/mcp`** so the plugin checks the Dremio token then forwards to the MCP server with the same `Authorization` header (relay only; MCP JSON-RPC/SSE is unchanged).
- **`langchain-gateway/`**: Python venv + **`scripts/setup-venv.ps1`**, **`scripts/pull-qwen-ollama.ps1`** (default test model **`qwen2.5:3b`**), **`scripts/run-gateway.ps1`** → **`POST /gateway/chat`** with **`Authorization: Bearer <Dremio token>`**; MCP URL defaults to **`http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp`** (`AICHAT_MCP_PROXY_URL`).
- **Step-by-step stack deploy (OSS + MCP + plugin + Ollama + gateway):** see **[DEPLOYMENT.md](DEPLOYMENT.md)** (Vietnamese).
- Open **`/aichat/chat.html`** for a minimal browser UI; **`GET /aichat/config`** for safe diagnostics.

---

## Why this does not modify OSS core

- Code lives under `tools/aichatbot-plugin`.
- Runs as a **separate process**, talking to Dremio only over **public REST APIs**.
- No changes required inside `dac/backend`.
