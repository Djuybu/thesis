# Hướng dẫn chạy stack OSS + AI chatbot trên 1 máy (WSL/Linux)

Tài liệu này là **bộ lệnh cụ thể đã được kiểm chứng** trên repo này: build → chạy
Dremio OSS, plugin AI chatbot, dremio-mcp (HTTP), gateway LangChain, Ollama
(qwen2.5:3b), rồi chat thật từ dữ liệu trong Dremio.

> Dùng `$REPO=/home/admin1/new/thesis` (thay nếu repo bạn ở chỗ khác).
> Mọi `<DREMIO_TOKEN>` thay bằng token thật lấy ở Bước 3.

---

## 0. Yêu cầu môi trường

| Mục | Phiên bản | Ghi chú |
|------|-----------|---------|
| OS | Ubuntu 22.04 / 24.04 (WSL OK) | |
| JDK | **21** | bắt buộc khi chạy Dremio + plugin |
| Python | **3.11+** | cho gateway |
| Maven | (dùng `./mvnw`) | wrapper sẵn trong repo |
| RAM | **≥ 6 GB free** | Dremio + plugin + mcp + gateway + Ollama |
| Đĩa | ~10 GB | bao gồm `~/.m2`, `target/`, model Ollama |

Cài 1 lần các tool cần thiết:

```bash
# zstd cho bộ cài Ollama
sudo apt-get update && sudo apt-get install -y zstd

# Ollama (LLM local)
curl -fsSL https://ollama.com/install.sh | sh

# uv (Python package manager nhanh, cho dremio-mcp + gateway)
curl -LsSf https://astral.sh/uv/install.sh | sh
source $HOME/.local/bin/env || export PATH="$HOME/.local/bin:$PATH"
```

---

## 1. Build (1 lần)

```bash
export REPO=/home/admin1/new/thesis
cd $REPO
./mvnw clean install -DskipTests
```

Sau khi xong:

- Dremio Community: `$REPO/distribution/server/target/dremio-community-*/dremio-community-*/`
- Plugin AI chatbot: `$REPO/tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar`

---

## 2. Bật Dremio OSS — terminal **#1**

```bash
export REPO=/home/admin1/new/thesis
DREMIO_DIR=$(ls -d $REPO/distribution/server/target/dremio-community-*/dremio-community-* | head -1)
$DREMIO_DIR/bin/dremio start

# kiểm tra
curl -s -o /dev/null -w "code=%{http_code}\n" http://127.0.0.1:9047
```

Mở UI: <http://localhost:9047>

- Lần đầu: tạo admin (nhớ `userName` + `password`).
- Tạo dataset: vào **Home (`@admin`)** → **Upload File** → chọn CSV/Parquet
  (ví dụ `green_tripdata_2025-01.csv`) → **Save as Dataset**.

Khi cần dừng: `$DREMIO_DIR/bin/dremio stop`

---

## 3. Lấy token Dremio

```bash
TOKEN=$(curl -s -X POST http://localhost:9047/apiv2/login \
  -H "Content-Type: application/json" \
  -d '{"userName":"admin","password":"YOUR_PASSWORD"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
echo "$TOKEN"
```

Token này (chuỗi ~26 ký tự) dùng cho **plugin** và làm "PAT" cho **dremio-mcp**.
OSS Community 26.x **không có** PAT thực sự, session token dùng được thay thế.

---

## 4. Khởi động Ollama + pull model — terminal **#2**

```bash
ollama serve &        # nếu chưa chạy nền
ollama pull qwen2.5:3b
ollama list

# warm-up (lần đầu ~60s, sau ~2s)
curl -s http://127.0.0.1:11434/api/generate \
  -d '{"model":"qwen2.5:3b","prompt":"hi","stream":false,"options":{"num_predict":3}}' | head -c 200; echo
```

`qwen2.5:3b` (~1.9 GB) là model cân bằng nhất cho máy 6–8 GB RAM. Hỗ trợ
tool-calling cho ReAct.

---

## 5. Chạy dremio-mcp HTTP — terminal **#3**

Tạo config 1 lần (PAT = token Dremio):

```bash
mkdir -p $HOME/.config/dremioai
echo "$TOKEN" > $HOME/.config/dremioai/pat.txt
chmod 600 $HOME/.config/dremioai/pat.txt

cat > $HOME/.config/dremioai/config.yaml <<'EOF'
dremio:
  uri: "http://localhost:9047"
  pat: "@~/.config/dremioai/pat.txt"
tools:
  server_mode: FOR_DATA_PATTERNS
EOF
```

Chạy MCP server (uv tự dựng venv lần đầu, ~30–60s):

```bash
cd $REPO/tools/dremio-mcp
$HOME/.local/bin/uv run dremio-mcp-server run \
  -c $HOME/.config/dremioai/config.yaml \
  --enable-streaming-http \
  --host 127.0.0.1 --port 8000
```

Phải thấy log `Uvicorn running on http://127.0.0.1:8000`. Để terminal này
chạy nguyên.

Test (terminal khác):

```bash
curl -s -i -X POST "http://127.0.0.1:8000/mcp/" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"clientInfo":{"name":"smoke","version":"1"}}}' | head -20
```

Phải trả `200 OK` và body event-stream chứa `serverInfo`.

---

## 6. Plugin AI chatbot — terminal **#4**

```bash
export REPO=/home/admin1/new/thesis
cd $REPO

export DREMIO_BASE_URL="http://localhost:9047"
export AICHAT_PORT="9191"
export AI_BACKEND_URL="http://127.0.0.1:11434/v1/chat/completions"
export AI_MODEL_DEFAULT="qwen2.5:3b"
export DREMIO_MCP_HTTP_BASE="http://127.0.0.1:8000"
export DREMIO_MCP_HTTP_PATH="/mcp"

java -jar tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar
```

Banner phải có: `Dremio MCP HTTP proxy target: http://127.0.0.1:8000`.

UI demo (chat thuần, **không** kết nối dataset): <http://localhost:9191/aichat/chat.html>

---

## 7. Gateway LangChain — terminal **#5**

Setup venv 1 lần:

```bash
cd $REPO/tools/aichatbot-plugin/langchain-gateway
$HOME/.local/bin/uv venv
source .venv/bin/activate
$HOME/.local/bin/uv pip install -r requirements.txt
```

Chạy gateway (mỗi lần):

```bash
cd $REPO/tools/aichatbot-plugin/langchain-gateway
source .venv/bin/activate

export OLLAMA_BASE_URL="http://127.0.0.1:11434"
# `ollama pull qwen3.5:9b` trước khi chạy gateway
export OLLAMA_MODEL="qwen3.5:9b"
# ⚠ trỏ THẲNG vào dremio-mcp (slash cuối là bắt buộc)
export AICHAT_MCP_PROXY_URL="http://127.0.0.1:8000/mcp/"

python -m uvicorn gateway_app:app --host 127.0.0.1 --port 9292
```

Banner: `Uvicorn running on http://127.0.0.1:9292`.

Swagger UI: <http://127.0.0.1:9292/docs>

> Vì sao trỏ thẳng `http://127.0.0.1:8000/mcp/` mà không qua plugin
> `/aichat/mcp-proxy`?  Plugin hiện không forward header `Mcp-Session-Id`
> mà streamable HTTP của MCP yêu cầu sau `initialize`. Đi thẳng đơn giản
> hơn cho chạy local; production có thể fix plugin để forward session.

---

## 8. Test end-to-end

### 8.1. Plugin chat thuần (không có dataset)

```bash
curl -s -X POST http://127.0.0.1:9191/aichat/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Giải thích lakehouse trong 3 câu.","model":"qwen2.5:3b","temperature":0.2}'
```

### 8.2. Gateway chat — LLM tự gọi MCP/SQL

```bash
curl -s -X POST http://127.0.0.1:9292/gateway/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message":"Có bao nhiêu chuyến trong bảng green_tripdata_2025-01?",
    "session_id":"demo","user_id":"admin",
    "model":"qwen3.5:9b","temperature":0,
    "strict_grounding":false,"data_query_workflow":false,
    "user_context":"{\"table_fqn\":\"\\\"@admin\\\".\\\"green_tripdata_2025-01\\\"\",\"hint\":\"Use RunSqlQuery on <table_fqn>\"}"
  }'
```

### 8.3. CLI chatbot biết schema (gọn nhất)

Helper trong `langchain-gateway/chatbot.py` — câu hỏi schema/aggregate đơn
giản trả lời deterministic, không phụ thuộc tool-call:

```bash
cd $REPO/tools/aichatbot-plugin/langchain-gateway
export DREMIO_TOKEN=$TOKEN

python3 chatbot.py ":cols"
python3 chatbot.py "Có bao nhiêu chuyến taxi?"
python3 chatbot.py "Trung bình tip_amount mỗi chuyến là bao nhiêu USD?"
python3 chatbot.py "trip_distance lớn nhất là bao nhiêu?"

# Hoặc interactive:
python3 chatbot.py
```

Đổi dataset:

```bash
TABLE_PATH="<space>/<dataset>" python3 chatbot.py "..."
```

---

## 9. Trạng thái stack đúng (kiểm tra nhanh)

```bash
ss -ltn | grep -E '9047|8000|9191|9292|11434'
```

Phải thấy 5 cổng cùng listening. Sai 1 cổng → quay lại đúng terminal đó.

```bash
curl -s http://127.0.0.1:9191/aichat/config
# kỳ vọng: "dremioMcpHttpConfigured":true, "aiBackendConfigured":true
```

---

## 10. Dừng / khởi động lại

| Service | Lệnh dừng | Lệnh khởi động |
|---------|-----------|----------------|
| Dremio | `$DREMIO_DIR/bin/dremio stop` | `$DREMIO_DIR/bin/dremio start` |
| Plugin (terminal #4) | `Ctrl+C` | xem Bước 6 |
| dremio-mcp (terminal #3) | `Ctrl+C` | xem Bước 5 |
| Gateway (terminal #5) | `Ctrl+C` | xem Bước 7 |
| Ollama | `pkill ollama` | `ollama serve &` |

Token Dremio mặc định hết hạn ~14 ngày. Khi gateway báo 401 → quay lại Bước 3
lấy token mới và **export lại** ở terminal đang chạy phần đó (gateway,
chatbot.py); cần update `~/.config/dremioai/pat.txt` rồi restart `dremio-mcp`.

---

## 11. Sự cố thường gặp

| Triệu chứng | Nguyên nhân + cách xử lý |
|-------------|--------------------------|
| `curl localhost:9047` ⇒ `ECONNREFUSED` | Dremio chưa start. Chạy lại Bước 2. |
| Plugin banner: `DREMIO_MCP_HTTP_BASE is not set` | Quên export biến. Restart Bước 6 với env đầy đủ. |
| Gateway 502 / `Failed to load MCP tools` | Sai URL MCP. Phải là `http://127.0.0.1:8000/mcp/` (có `/`). |
| Gateway 502 với `Mcp-Session-Id`/400 | Đang trỏ qua plugin; đổi sang URL trực tiếp dremio-mcp. |
| Gateway 401 | Token Dremio hết hạn. Login lại Bước 3, update env + `pat.txt`. |
| `qwen2.5:3b` trả lời rỗng / sai tool | Nhỏ → dùng `chatbot.py` (deterministic) hoặc thêm `user_context` rõ FQN. |
| Llama 3.2 trả lời rỗng cho `/gateway/chat` | Llama 3.2 yếu tool-calling, đổi sang `qwen2.5:3b`. |
| `bind: address already in use 11434` | Ollama đã chạy nền, đừng chạy `ollama serve` thêm. |
| Build chỉ plugin lỗi `dremio-errorprone` | Thêm `-Derrorprone.skip=true` hoặc đã `mvn install` full repo. |

---

## 12. Kiến trúc tham chiếu

```
Trình duyệt / curl
        │
        ├─► POST :9292/gateway/chat ─► Gateway (LangChain ReAct)
        │                                 │
        │                                 ├─► :11434  Ollama (qwen2.5:3b)
        │                                 └─► :8000/mcp/  dremio-mcp (HTTP)
        │                                            │
        │                                            └─► :9047  Dremio OSS
        │
        └─► POST :9191/aichat/ask ─► Plugin (chat thuần, không tool)
                                          │
                                          └─► :11434  Ollama
```

Chi tiết kiến trúc gateway: [langchain-gateway/README.md](langchain-gateway/README.md),
[langchain-gateway/LANGCHAIN-LANGGRAPH.md](langchain-gateway/LANGCHAIN-LANGGRAPH.md).

---

## 13. Tham chiếu tài liệu khác trong repo

- Build toàn bộ repo: [../../BUILD-FULL-VI.md](../../BUILD-FULL-VI.md)
- Tổng quan plugin: [README.md](README.md)
- Triển khai chỉ chatbot (không gateway): [CHATBOT-DEPLOY.md](CHATBOT-DEPLOY.md)
- Deployment đầy đủ (PowerShell + Linux): [DEPLOYMENT.md](DEPLOYMENT.md)
