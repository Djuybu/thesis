# Hướng dẫn triển khai đầy đủ: Dremio OSS + AI Chatbot Plugin + dremio-mcp + LangChain Gateway

Tài liệu này mô tả **từng bước** để chạy cùng lúc: **Dremio OSS**, **plugin aichatbot** (JAR), **dremio-mcp** (streaming HTTP), **Ollama** (LLM), và **gateway LangChain** (một API chat kết hợp LLM + MCP tools).

Tham chiếu thêm: [README.md](README.md) (API, biến môi trường plugin, bảo mật).

---

## 1. Tổng quan luồng dữ liệu

```text
[Trình duyệt / curl]
        │
        ├─► POST http://127.0.0.1:9292/gateway/chat  (Bearer = token Dremio)
        │         │
        │         └─► LangChain gateway
        │                 ├─► Ollama :11434  (suy luận + tool-calling)
        │                 └─► Plugin :9191/aichat/mcp-proxy?path=/mcp
        │                           │
        │                           ├─► Dremio OSS :9047  (xác thực token)
        │                           └─► dremio-mcp :8000/mcp  (MCP streamable HTTP)
        │                                     │
        │                                     └─► Dremio OSS :9047  (REST/API theo config MCP)
        │
        └─► (tuỳ chọn) POST http://127.0.0.1:9191/aichat/ask  → chỉ LLM, không agent MCP
```

**Thứ tự phụ thuộc khi khởi động:** Dremio OSS → dremio-mcp → plugin aichatbot → Ollama (thường đã chạy nền) → gateway.

---

## 2. Yêu cầu trước khi cài

| Thành phần | Ghi chú |
|------------|---------|
| **Dremio OSS** | Đang chạy, ví dụ `http://localhost:9047`. Bạn cần **PAT** (Personal Access Token) hoặc token đăng nhập hợp lệ để gọi API. |
| **Java 11+** | Để chạy JAR plugin sau khi `mvn package`. |
| **Python 3.11+** | Cho dremio-mcp (khuyến nghị dùng **uv**) và cho gateway (`langchain-gateway`). |
| **Git** | Clone repo `dremio-mcp` vào `tools/dremio-mcp` (thư mục này **bị .gitignore** trong repo OSS — chỉ dùng cục bộ). |
| **[uv](https://docs.astral.sh/uv/)** | Cài để chạy `dremio-mcp-server` theo hướng dẫn upstream. |
| **[Ollama](https://ollama.com/)** | Cho LLM local; gateway dùng **ChatOllama** trỏ tới Ollama. |

**Cổng mặc định trong hướng dẫn này** (đổi nếu trùng máy bạn):

| Dịch vụ | Cổng |
|---------|------|
| Dremio OSS | 9047 |
| dremio-mcp (HTTP) | 8000 |
| Plugin aichatbot | 9191 |
| Ollama | 11434 |
| LangChain gateway | 9292 |

---

## 3. Bước A — Lấy token Dremio (PAT)

1. Đăng nhập UI Dremio OSS.
2. Tạo **Personal Access Token** (hoặc dùng token từ luồng login API mà tổ chức bạn cho phép).
3. Giữ token an toàn; mọi lệnh `curl` bên dưới thay `<DREMIO_TOKEN>` bằng giá trị thực (có thể là chuỗi JWT hoặc PAT tùy cấu hình OSS).

Token này dùng cho:

- Header `Authorization: Bearer <DREMIO_TOKEN>` khi gọi plugin và gateway.

---

## 4. Bước B — Clone và cấu hình dremio-mcp

### 4.1 Clone (một lần)

Từ thư mục gốc repo **dremio-oss** (đổi `D:\dev\dremio-oss` thành đường dẫn máy bạn):

```powershell
cd D:\dev\dremio-oss
git clone https://github.com/dremio/dremio-mcp.git tools\dremio-mcp
```

### 4.2 File cấu hình YAML

1. Sao chép file mẫu trong repo OSS:

   ```text
   tools\aichatbot-plugin\langchain-gateway\example-mcp-oss.yaml
   ```

2. Lưu thành file riêng, ví dụ `D:\dev\dremio-oss\local\mcp-oss.yaml` (đừng commit file chứa PAT).

3. Sửa nội dung tối thiểu:

   - `dremio.uri`: URL Dremio OSS (ví dụ `http://localhost:9047`).
   - `dremio.pat`: PAT thật thay cho `REPLACE_WITH_YOUR_DREMIO_PAT`.

Tuỳ nhu cầu có thể tạo config bằng CLI upstream (`dremio-mcp-server config create …`) — xem [README dremio-mcp](https://github.com/dremio/dremio-mcp/blob/main/README.md).

### 4.3 Chạy MCP ở chế độ streaming HTTP

Trong thư mục `tools\dremio-mcp`:

```powershell
cd D:\dev\dremio-oss\tools\dremio-mcp
uv run dremio-mcp-server run -c D:\dev\dremio-oss\local\mcp-oss.yaml --enable-streaming-http --host 127.0.0.1 --port 8000
```

Giữ cửa sổ terminal này mở. MCP lắng nghe HTTP; đường dẫn endpoint thường là **`/mcp`** (nếu phiên bản khác, xem log hoặc `--help` của lệnh `run`).

**Kiểm tra nhanh:** nếu có lỗi auth hoặc URI, sửa YAML và khởi động lại MCP.

---

## 5. Bước C — Build và chạy plugin aichatbot

### 5.1 Build JAR (mỗi khi đổi code plugin)

```powershell
cd D:\dev\dremio-oss
.\mvnw.cmd -pl tools/aichatbot-plugin -DskipTests package
```

JAR nằm dưới `tools\aichatbot-plugin\target\dremio-aichatbot-plugin-*.jar`.

### 5.2 Biến môi trường và chạy (PowerShell)

```powershell
cd D:\dev\dremio-oss

$env:DREMIO_BASE_URL="http://localhost:9047"
$env:AICHAT_PORT="9191"

# Bắt buộc để bật /aichat/mcp-proxy — trùng host/port MCP vừa chạy
$env:DREMIO_MCP_HTTP_BASE="http://127.0.0.1:8000"
$env:DREMIO_MCP_HTTP_PATH="/mcp"

# Cho /aichat/ask và chat.html (Ollama OpenAI-compatible)
$env:AI_BACKEND_URL="http://127.0.0.1:11434/v1/chat/completions"
$env:AI_MODEL_DEFAULT="qwen2.5:3b"

java -jar tools\aichatbot-plugin\target\dremio-aichatbot-plugin-*.jar
```

**Kiểm tra:**

- Trình duyệt: `http://localhost:9191/health` → JSON `status":"ok"`.
- `http://localhost:9191/aichat/config` → `dremioMcpHttpConfigured`: **true**.

---

## 6. Bước D — Ollama và model (Qwen để test)

1. Cài Ollama, bật dịch vụ (Windows thường chạy nền sau khi cài).

2. Kéo model nhỏ để test (ví dụ Qwen 3B):

   ```powershell
   cd D:\dev\dremio-oss\tools\aichatbot-plugin\langchain-gateway\scripts
   .\pull-qwen-ollama.ps1
   ```

   Hoặc tay: `ollama pull qwen2.5:3b`

3. Gateway mặc định dùng `OLLAMA_MODEL=qwen2.5:3b` và `OLLAMA_BASE_URL=http://127.0.0.1:11434` (đổi bằng biến môi trường nếu Ollama ở máy khác).

---

## 7. Bước E — Gateway LangChain (Python)

### 7.1 Tạo venv và cài dependency (một lần)

```powershell
cd D:\dev\dremio-oss\tools\aichatbot-plugin\langchain-gateway\scripts
.\setup-venv.ps1
```

### 7.2 Chạy gateway

Cửa sổ PowerShell **mới** (plugin và MCP vẫn đang chạy):

```powershell
cd D:\dev\dremio-oss\tools\aichatbot-plugin\langchain-gateway\scripts

$env:AICHAT_MCP_PROXY_URL="http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp"
$env:OLLAMA_MODEL="qwen2.5:3b"
$env:GATEWAY_HOST="127.0.0.1"
$env:GATEWAY_PORT="9292"

.\run-gateway.ps1
```

**Các biến tuỳ chọn khác** (xem [README.md — Gateway](README.md)):

- `OLLAMA_BASE_URL`, `GATEWAY_SYSTEM_PROMPT`, `AICHAT_MCP_TIMEOUT_SECONDS`, `AICHAT_MCP_SSE_READ_TIMEOUT_SECONDS`.

**Kiểm tra:** `http://127.0.0.1:9292/health`

---

## 8. Bước F — Gọi thử end-to-end

### 8.1 Chat qua gateway (LLM + MCP tools)

```powershell
curl.exe -s -X POST "http://127.0.0.1:9292/gateway/chat" `
  -H "Authorization: Bearer <DREMIO_TOKEN>" `
  -H "Content-Type: application/json" `
  -d "{\"message\":\"Mô tả ngắn các nguồn hoặc space tôi có thể truy vấn; dùng công cụ MCP nếu cần.\"}"
```

Phản hồi JSON có trường `answer` (và `model`, `mcp_proxy_url`).

### 8.2 (Tuỳ chọn) Chỉ LLM qua plugin, không agent

Mở `http://localhost:9191/aichat/chat.html` hoặc:

```powershell
curl.exe -s -X POST "http://localhost:9191/aichat/ask" `
  -H "Authorization: Bearer <DREMIO_TOKEN>" `
  -H "Content-Type: application/json" `
  -d "{\"prompt\":\"Xin chào, bạn là ai?\",\"model\":\"qwen2.5:3b\"}"
```

### 8.3 (Tuỳ chọn) Thử trực tiếp MCP qua proxy plugin

Chỉ dùng khi bạn muốn gỡ lỗi MCP thuần (JSON-RPC theo spec MCP):

```powershell
curl.exe -s -X POST "http://localhost:9191/aichat/mcp-proxy?path=/mcp" `
  -H "Authorization: Bearer <DREMIO_TOKEN>" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json, text/event-stream" `
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
```

---

## 9. Checklist sau triển khai

- [ ] Dremio OSS phản hồi tại `DREMIO_BASE_URL`.
- [ ] `dremio-mcp` chạy với `--enable-streaming-http`, đúng `uri` / `pat` trong YAML.
- [ ] Plugin có `DREMIO_MCP_HTTP_BASE` trỏ đúng tới MCP; `/aichat/config` báo MCP đã cấu hình.
- [ ] Ollama chạy và đã `pull` đúng model (`ollama list`).
- [ ] Gateway `/health` OK; `POST /gateway/chat` với Bearer token trả lời được (có thể chậm lần đầu do tải model/tool).

---

## 10. Dừng dịch vụ

Thứ tự gợi ý: dừng gateway (Ctrl+C) → dừng plugin JAR → dừng dremio-mcp → tắt Ollama nếu muốn.

---

## 11. Gỡ lỗi thường gặp

| Hiện tượng | Hướng xử lý |
|-------------|-------------|
| `401` từ plugin / gateway | Token Dremio sai hoặc hết hạn; tạo PAT mới hoặc đăng nhập lại. |
| `502` từ `/aichat/mcp-proxy` | MCP không chạy, sai `DREMIO_MCP_HTTP_BASE`, hoặc sai `path` (thường `/mcp`). |
| Gateway báo lỗi MCP / tools | Kiểm tra plugin + token; mở log `dremio-mcp`; đảm bảo PAT trong YAML MCP khớp quyền với cluster. |
| Gateway báo lỗi Ollama | `ollama serve`, firewall; đúng `OLLAMA_BASE_URL` / đã `ollama pull` model. |
| Plugin trong Docker không tới `127.0.0.1:11434` | Dùng IP máy host hoặc `host.docker.internal` (Docker Desktop) cho `AI_BACKEND_URL` / biến tương đương. |

Bảng mở rộng: [README — Xử lý sự cố](README.md#xử-lý-sự-cố).

---

## 12. Bản production / mạng tin cậy

Hướng dẫn trên phù hợp **máy cục bộ / lab**. Khi đưa lên môi trường thật:

- Bật **HTTPS** phía reverse proxy; thu hẹp **CORS**; không expose gateway ra internet công khai mà không có thêm lớp auth/rate limit.
- Xoay PAT định kỳ; không lưu PAT trong git.

Chi tiết: [README — Bảo mật](README.md#bảo-mật--lưu-ý).
