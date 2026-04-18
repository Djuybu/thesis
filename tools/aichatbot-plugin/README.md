# AI Chat Bot Plugin (standalone) — Dremio OSS + **local LLM**

Plugin chạy **tách biệt** khỏi DAC/OSS core: xác thực bằng token Dremio (`/apiv2/login`), lấy ngữ cảnh user (`/apiv2/user/{username}`), rồi gọi **LLM chạy trên máy bạn** (khuyến nghị API tương thích OpenAI: **Ollama**, **LM Studio**, **vLLM**, …).

---

## Mục lục

1. [Kiến trúc](#kiến-trúc)
2. [Chức năng](#chức-năng)
3. [Cài và chạy local LLM (quan trọng)](#cài-và-chạy-local-llm-quan-trọng)
4. [Build & chạy plugin](#build--chạy-plugin)
5. [Biến môi trường](#biến-môi-trường)
6. [API](#api)
7. [Giao diện web tối thiểu](#giao-diện-web-tối-thiểu)
8. [Bảo mật & lưu ý](#bảo-mật--lưu-ý)
9. [Xử lý sự cố](#xử-lý-sự-cố)
10. [English summary](#english-summary)

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

---

## API

### `GET /health`

Trạng thái service.

### `GET /aichat/config`

JSON: `dremioBaseUrl`, `llmMode`, `aiBackendConfigured`, `defaultModel`, `requestTimeoutSeconds`, `unwrapOpenAiContent`.

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

---

## Giao diện web tối thiểu

Mở **http://&lt;AICHAT_HOST&gt;:&lt;port&gt;/aichat/chat.html** — nhập URL plugin, token Dremio, model local, prompt → gửi `POST /aichat/ask`.

---

## Bảo mật & lưu ý

- **Token Dremio** có quyền như user đó; không log token, không commit token vào git.
- Plugin **không thay thế** kiểm soát truy cập dữ liệu Dremio: chỉ forward token tới API Dremio; LLM chỉ nhận nội dung bạn đưa (system + user + đoạn profile JSON).
- CORS `*` chỉ phù hợp **mạng tin cậy** / lab. Production nên đặt reverse proxy (nginx) và thu hẹp CORS, TLS, rate limit.
- Local LLM: dữ liệu không gửi ra cloud nếu endpoint trỏ tới máy bạn — kiểm tra kỹ `AI_BACKEND_URL`.

---

## Xử lý sự cố

| Hiện tượng | Gợi ý |
|------------|--------|
| `401` từ plugin | Token Dremio sai/hết hạn; thử `GET /aichat/context` trước. |
| Timeout LLM | Tăng `AI_REQUEST_TIMEOUT_SECONDS`; kiểm tra GPU/CPU; model nhỏ hơn. |
| Connection refused tới `127.0.0.1:11434` | Ollama chưa chạy hoặc plugin chạy trong Docker không thấy localhost host. |
| JSON lỗi / `answer` trống | Đặt `AI_UNWRAP_OPENAI_CONTENT=false` để xem raw response LLM; kiểm tra server có đúng OpenAI Chat API không. |
| Model not found | `ollama pull <tên>` hoặc đổi `AI_MODEL_DEFAULT` / field `model` trong request. |

---

## English summary

- **Standalone** JAR: validates **Dremio** bearer token via `/apiv2/login`, optionally loads user JSON via `/apiv2/user/{userName}`, then calls your **OpenAI-compatible** endpoint (default), ideal for **Ollama** / **LM Studio** on `localhost`.
- Set `AI_BACKEND_URL` to e.g. `http://127.0.0.1:11434/v1/chat/completions`, `AI_MODEL_DEFAULT=llama3.2`, tune `AI_REQUEST_TIMEOUT_SECONDS` for slow local inference.
- **`AI_LLM_MODE=custom`** keeps the older JSON bridge for a self-hosted gateway.
- Open **`/aichat/chat.html`** for a minimal browser UI; **`GET /aichat/config`** for safe diagnostics.

---

## Why this does not modify OSS core

- Code lives under `tools/aichatbot-plugin`.
- Runs as a **separate process**, talking to Dremio only over **public REST APIs**.
- No changes required inside `dac/backend`.
