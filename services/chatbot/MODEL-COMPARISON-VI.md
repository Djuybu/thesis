# So sánh 3 model LLM (báo cáo / thí nghiệm)

Cùng một pipeline chatbot (guardrail → discovery → HITL → SQL → finalize), chỉ đổi **backend LLM** qua file `.env` và **restart gateway**.

| # | Provider | Ví dụ model | Cấu hình `.env` |
|---|----------|-------------|-----------------|
| 1 | **Ollama (local)** | `qwen2.5:3b` | `LLM_PROVIDER=ollama` |
| 2 | **OpenAI API** | `gpt-4o-mini` | `LLM_PROVIDER=openai` + `OPENAI_API_KEY` |
| 3 | **Google Gemini** | `gemini-2.0-flash` | `LLM_PROVIDER=gemini` + `GEMINI_API_KEY` |

## Chuẩn bị chung (một lần)

1. Dremio `:9047`, MCP `./run-mcp.sh`, `mcp-oss.yaml` có PAT hợp lệ.
2. Cài dependency Gemini (một lần sau khi pull code):

   ```bash
   cd ~/dremio-oss/thesis/tools/dremio-mcp
   uv sync
   ```

3. Cùng **câu hỏi thử** cho cả 3 lần chạy (ghi vào báo cáo), ví dụ:

   > top 10 chuyến xe có total_amount cao nhất trong Samples."samples.dremio.com"."NYC-taxi-trips.csv"

4. Ghi lại cho mỗi model (mỗi **request** chat/resume; cộng 3 request nếu báo cáo full HITL):
   - **Thời gian:** `elapsed_ms` (ms) — log gateway hoặc dòng ⏱ trên UI
   - **Token LLM:** `token_usage.total_tokens`, `input_tokens`, `output_tokens`
   - **Theo bước:** `step_timings_ms` (ms), `step_token_usage` (token từng node: `guardrail`, `sql_gen`, `finalize`, …)
   - Chất lượng: SQL đúng?, tiếng Việt?, lỗi guardrail?

### File log JSONL (khuyến nghị cho báo cáo)

Chạy `./run-gateway.sh` → mỗi lần hỏi / resume ghi **một dòng** vào:

`services/chatbot/logs/agent-runs.jsonl`

Mỗi dòng chứa đủ: câu hỏi, model, provider, trạng thái, câu trả lời, `execution_result`, `elapsed_ms`, `token_usage`, `step_timings_ms`, SQL đề xuất (`proposed_sql`), …

```bash
# Bảng nhanh: provider, model, thời gian (s), token
jq -r '[.timestamp,.llm_provider,.model,(.elapsed_ms/1000|tostring+"s"),(.token_usage.total_tokens//0)] | @tsv' \
  services/chatbot/logs/agent-runs.jsonl

# Gộp 3 bước HITL theo thread_id
jq -s 'group_by(.thread_id) | map({
  thread: .[0].thread_id,
  provider: .[0].llm_provider,
  model: .[0].model,
  total_ms: (map(.elapsed_ms // 0) | add),
  total_tokens: (map(.token_usage.total_tokens // 0) | add)
})' services/chatbot/logs/agent-runs.jsonl
```

Tắt file log: `AGENT_RUN_LOG=0` trong `.env`.

### Đọc số liệu từ log terminal gateway

```text
Agent chat_resume finished thread_id=... elapsed_ms=45230
Agent run log appended path=.../agent-runs.jsonl phase=chat_resume thread_id=...
Agent chat_resume token_usage={'input_tokens': 3200, 'output_tokens': 450, 'total_tokens': 3650}
Agent chat_resume step_token_usage={'sql_gen': {...}, 'finalize': {...}}
```

### Đọc từ API (một request)

```bash
curl -s -X POST http://127.0.0.1:9292/aichat/v1/chat/resume \
  -H "Authorization: Bearer <DREMIO_PAT>" \
  -H "Content-Type: application/json" \
  -d '{"thread_id":"hitl-...","action":"approve"}' | python3 -m json.tool
```

Tìm: `"elapsed_ms"`, `"token_usage"`, `"step_token_usage"`, `"step_timings_ms"`.

**Lưu ý Ollama:** một số model local **không** báo token → `total_tokens` có thể là `0`; so sánh thời gian vẫn hợp lệ.

---

## Chạy model 1 — Ollama (Qwen local)

```bash
# Terminal: ollama
ollama serve
ollama pull qwen2.5:3b
```

`.env`:

```bash
LLM_PROVIDER=ollama
OLLAMA_MODEL=qwen2.5:3b
OLLAMA_BASE_URL=http://127.0.0.1:11434
# Comment / xóa OPENAI_API_KEY và GEMINI_API_KEY để tránh nhầm provider
```

```bash
cd ~/dremio-oss/thesis/services/chatbot
./run-gateway.sh
```

Kiểm tra log: `LLM_PROVIDER=ollama`, `OLLAMA_MODEL=qwen2.5:3b`.

---

## Chạy model 2 — OpenAI (GPT-4o-mini)

Dừng gateway (`Ctrl+C`), sửa `.env`:

```bash
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o-mini
AGENT_RESPONSE_LOCALE=vi
```

```bash
./run-gateway.sh
```

Log: `LLM_PROVIDER=openai`, `OPENAI_API_KEY=<set>`.

```bash
curl -s http://127.0.0.1:9292/aichat/v1/config
# "llm_provider":"openai", "default_model":"gpt-4o-mini"
```

---

## Chạy model 3 — Google Gemini

API key: [Google AI Studio](https://aistudio.google.com/apikey) → tạo key.

Dừng gateway, sửa `.env`:

```bash
LLM_PROVIDER=gemini
GEMINI_API_KEY=AIza...
GEMINI_MODEL=gemini-2.0-flash
# Hoặc: GOOGLE_API_KEY=AIza...
AGENT_RESPONSE_LOCALE=vi
```

```bash
./run-gateway.sh
```

Log: `LLM_PROVIDER=gemini`, `GEMINI_API_KEY=<set>`.

```bash
curl -s http://127.0.0.1:9292/aichat/v1/config
# "llm_provider":"gemini", "default_model":"gemini-2.0-flash"
```

**Model khác (Gemini 1.5):** `GEMINI_MODEL=gemini-1.5-flash` hoặc `gemini-1.5-pro`.

---

## Mẹo khi so sánh công bằng

- **Cùng thread_id hoặc phiên mới** mỗi model để tránh state LangGraph cũ.
- **Cùng bước HITL** (approve metadata + approve SQL) cho mỗi lần.
- Chỉ đổi **một** provider mỗi lần; trong `.env` chỉ để **một** loại API key active.
- Sau mỗi lần đổi `.env` → **bắt buộc restart** `./run-gateway.sh`.
- Xuất log: `elapsed_ms`, `token_usage`, `step_timings_ms`, `step_token_usage` từ terminal gateway.
- Tắt chi tiết token theo node: `AGENT_STEP_TOKEN_USAGE=0` (vẫn có `token_usage` tổng).

## Bảng mẫu cho báo cáo

Ghi **tổng 3 request** (chat + resume metadata + resume SQL) hoặc chỉ request cuối (có `finalize`) — ghi rõ trong báo cáo.

| Tiêu chí | Ollama (qwen2.5:3b) | OpenAI (gpt-4o-mini) | Gemini (gemini-2.0-flash) |
|----------|---------------------|----------------------|---------------------------|
| Tổng thời gian (ms) — 3 request | | | |
| Token input (tổng) | | | |
| Token output (tổng) | | | |
| Token total (tổng) | | | |
| Thời gian sinh SQL (ms) | | | |
| Token sinh SQL | | | |
| Thời gian tóm tắt (ms) | | | |
| Token tóm tắt | | | |
| SQL chạy được | Có / Không | | |
| Trả lời tiếng Việt | Có / Không | | |
| Ghi chú | | | |

---

## Sự cố

| Lỗi | Xử lý |
|-----|--------|
| `No module named langchain_google_genai` | `cd tools/dremio-mcp && uv sync` |
| Gateway vẫn báo ollama | Save `.env`, `LLM_PROVIDER=gemini`, restart gateway |
| Gemini 429 / quota | Đổi model nhỏ hơn hoặc đợi quota |
| Structured SQL lỗi trên Gemini | Thử `gemini-2.0-flash`; tăng `AGENT_STRUCTURED_LLM_TIMEOUT_SECONDS` |
