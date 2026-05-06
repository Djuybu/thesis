# Triển khai chatbot (chỉ phần chat)

Tài liệu này mô tả **cách chạy chatbot** — không lặp hướng dẫn cài đặt toàn bộ Dremio OSS, cluster, hay lakehouse. Giả định bạn đã có **URL Dremio** để lấy token (plugin dùng token đó để xác thực).

---

## Chatbot gồm những gì?

| Thành phần | Vai trò |
|------------|---------|
| **Plugin JAR** (`aichatbot`) | HTTP service: kiểm tra `Bearer` với Dremio, gọi LLM, (tuỳ chọn) proxy MCP. |
| **LLM cục bộ** | Khuyến nghị **Ollama** — API tương thích OpenAI tại `…/v1/chat/completions`. |
| **Gateway LangChain** (tuỳ chọn) | Python FastAPI: agent **ReAct** + MCP + RAG — endpoint **`POST /gateway/chat`**. |

Hai cách dùng phổ biến:

1. **Chat đơn giản** — chỉ plugin + Ollama: `POST /aichat/ask` (LLM trả lời trực tiếp, **không** gọi tool Dremio).
2. **Chat thông minh với dữ liệu** — plugin + Ollama + **dremio-mcp** + **langchain-gateway**: `POST /gateway/chat` (agent dùng MCP để catalog/SQL).

---

## Bước 1 — LLM (Ollama)

```bash
# Cài Ollama (theo https://ollama.com/), rồi:
ollama pull llama3.2
# hoặc model khác khớp AI_MODEL_DEFAULT / OLLAMA_MODEL
```

Đảm bảo API mở (mặc định): `http://127.0.0.1:11434/v1/chat/completions`.

---

## Bước 2 — Build plugin

Từ thư mục gốc repo (có `mvnw`). Artifact `dremio-errorprone` không có trên Maven Central — cần **bỏ ErrorProne** khi build cục bộ:

```bash
./mvnw -pl tools/aichatbot-plugin -DskipTests -Derrorprone.skip=true package
```

Hoặc chạy một lần: [`build-for-local-test.sh`](build-for-local-test.sh) (build JAR + tạo `langchain-gateway/.venv` nếu máy có `python3-venv`).

JAR: `tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar`.

---

## Bước 3A — Chạy **chỉ chatbot** (không agent MCP)

Biến tối thiểu:

| Biến | Ví dụ |
|------|--------|
| `DREMIO_BASE_URL` | `http://localhost:9047` |
| `AICHAT_PORT` | `9191` |
| `AI_BACKEND_URL` | `http://127.0.0.1:11434/v1/chat/completions` |
| `AI_MODEL_DEFAULT` | `llama3.2` |

```bash
export DREMIO_BASE_URL=http://localhost:9047
export AICHAT_PORT=9191
export AI_BACKEND_URL=http://127.0.0.1:11434/v1/chat/completions
export AI_MODEL_DEFAULT=llama3.2
export AI_REQUEST_TIMEOUT_SECONDS=180

java -jar tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar
```

**Kiểm tra**

- `GET http://localhost:9191/health`
- UI: `http://localhost:9191/aichat/chat.html`
- Chat API: `POST http://localhost:9191/aichat/ask` — header `Authorization: Bearer <DREMIO_TOKEN>`, body `{"prompt":"..."}`.

**Lưu ý:** Cần token Dremio hợp lệ (plugin gọi `/apiv2/login`). Không cần bật MCP hay gateway cho luồng này.

---

## Bước 3B — Chatbot **agent** (LangChain + MCP qua plugin)

Thêm:

1. Chạy **dremio-mcp** (HTTP streaming) và cấu hình PAT/OAuth theo upstream.
2. Plugin với proxy MCP:

```bash
export DREMIO_MCP_HTTP_BASE=http://127.0.0.1:8000
export DREMIO_MCP_HTTP_PATH=/mcp
# ... cùng các biến như 3A ...
java -jar tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar
```

3. **langchain-gateway** (Python):

```bash
cd tools/aichatbot-plugin/langchain-gateway
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_MODEL=llama3.2
export AICHAT_MCP_PROXY_URL=http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp

python -m uvicorn gateway_app:app --host 127.0.0.1 --port 9292
```

**Chat agent:** `POST http://127.0.0.1:9292/gateway/chat` — cùng `Authorization: Bearer <DREMIO_TOKEN>`, body JSON xem [langchain-gateway/README.md](langchain-gateway/README.md).

---

## Tóm tắt endpoint

| Endpoint | Khi nào dùng |
|----------|----------------|
| `POST /aichat/ask` | Chat trực tiếp với LLM qua plugin (đơn giản). |
| `POST /gateway/chat` | Agent LangGraph + tool MCP (+ RAG tuỳ cấu hình) — qua gateway Python. |
| `GET /aichat/chat.html` | Form thử nhanh (gọi `/aichat/ask`, không gọi gateway). |

---

## Gỡ lỗi nhanh (chatbot)

| Hiện tượng | Hướng xử lý |
|------------|-------------|
| `401` | Token Dremio sai/hết hạn. |
| Timeout LLM | Tăng `AI_REQUEST_TIMEOUT_SECONDS`; model nhỏ hơn. |
| Không kết nối `127.0.0.1:11434` | Ollama chưa chạy; hoặc plugin trong Docker — đổi host (vd. `host.docker.internal`). |
| Gateway không load MCP | Bật `DREMIO_MCP_HTTP_BASE`, MCP đang chạy, `AICHAT_MCP_PROXY_URL` trỏ đúng plugin. |

Chi tiết biến môi trường và API đầy đủ: [README.md](README.md). Triển khai stack OSS đầy đủ (Dremio + MCP + plugin + gateway): [DEPLOYMENT.md](DEPLOYMENT.md).
