# Báo cáo tiến độ tháng — Dự án Dremio AI Chatbot

**Branch:** `aichatbot-merge` · **Repository:** `Djuybu/thesis`

Báo cáo tóm tắt 3 hạng mục công việc đã thực hiện trong tháng:
"""
Thêm tên thành viên (Thành viên nào làm tính năng gì?)
"""

1. Giao diện cho Chatbot (UI)
2. Backend logic cho LLM (LangGraph)
3. Tính năng Autonomous Reflection

---

## 1. Giao diện cho Chatbot

### 1.1 Mục tiêu

Xây dựng widget chat AI nhúng trực tiếp vào Dremio Analyst Center (DAC), cho phép người dùng hỏi đáp về dữ liệu/SQL với cơ chế **phê duyệt SQL trước khi chạy (HITL)**.

### 1.2 Công việc đã thực hiện

**Vị trí code:** `dac/ui/src/components/AIChatbot/`.
(Bỏ bảng file | vai trò đi)


**Stack:** React 18 + TypeScript, sử dụng prettier để format

**Tính năng đã hoàn thành (A tự viết lại phần này cho giống người viết hơn giúp em với + chèn thêm ảnh):**


- Nút FAB nổi góc dưới phải, mở modal panel 1280×800px (responsive).
- Bố cục 2 cột: khung chat + sidebar lịch sử.
- **Quản lý phiên hội thoại**: tạo mới, ghim, đổi tên, tìm kiếm, lưu `localStorage`.
- **Human-in-the-loop**: 2 lần phê duyệt — metadata + SQL. Có nút **Approve / Edit & Run / Reject**.

- Quick prompts gợi ý cho phiên mới.
- Like/Dislike, Copy, Edit prompt, Run SQL (đẩy sang `/new_query`).
- Badge metadata thời gian thực: Model, Phiên, Latency, API status.

**Tích hợp UI:** tại `dac/ui/src/AdditionalAppElements.tsx`.

### 1.3 Vấn đề còn tồn tại

- `AIChatbot.tsx` 815 dòng, monolithic — cần tách `<MessageList>`, `<InputBar>`, `<HistoryPanel>`.
- `localStorage` không scale khi sessions >1000 message.
- Chưa hỗ trợ streaming token (UI phải đợi toàn bộ response).

### 1.4 Kế hoạch tiếp theo

- Refactor tách sub-component.
- Thêm SSE streaming hiển thị token theo thời gian thực.
- Lưu feedback Like/Dislike lên backend (hiện chỉ local).

---

## 2. Backend Logic cho LLM (LangGraph)

### 2.1 Mục tiêu

Xây dựng AI agent điều phối luồng **hỏi đáp dữ liệu → sinh SQL → thực thi** trên Dremio bằng **LangGraph**, đảm bảo người dùng có quyền **phê duyệt mỗi bước nhạy cảm**.

### 2.2 Công việc đã thực hiện

**Vị trí code:** `tools/dremio-mcp/src/dremioai/` (gateway + agent).

```text
gateway/    → FastAPI REST (3 endpoint /aichat/v1/*)
agent/      → LangGraph StateGraph
  graph.py    → wire 13 node + 8 conditional edges + compile
  state.py    → AgentState (TypedDict)
  nodes/      → guardrail.py, discovery.py, sql_flow.py, common.py
  tools.py    → MCP tool locator
  mcp_client.py → MultiServerMCPClient (Dremio MCP HTTP)
```

**Stack:** FastAPI, LangGraph (`StateGraph` + `InMemorySaver`), `langchain-ollama` (`ChatOllama` qwen3.5:4b), Pydantic, MCP qua `langchain-mcp-adapters`.

**Đồ thị 13 node** (xem chi tiết tại `langgraph-state-graph.svg`):

```text
START → guardrail
      ├→ greetings → END                (regex fast-path)
      ├→ guardrail_reject → END         (UNSAFE)
      └→ discovery
             ├→ early_end → END         (list sources/catalog short-circuit)
             ├→ error_end → END
             └→ pick_and_schema
                    ├→ early_end → END  (schema short-circuit)
                    ├→ error_end → END
                    └→ metadata_confirmation  ⏸ HITL
                           ├→ reject_end → END
                           └→ sql_gen
                                  ├→ error_end → END
                                  └→ refinement     ⏸ HITL
                                         ├→ reject_end → END
                                         └→ execute → finalize → END
```

**Tính năng đã hoàn thành (Tương tự, a tự viết lại giúp em. Tạm thời viết đơn giản cho mấy tính năng: LLM đã đưa ra phản hồi, có thể chọn đúng bảng, biết từ chối yêu cầu vô lý...):**

- **Guardrail 3 lớp** (regex GREETING → regex DATA_SAFE → LLM classify) — tránh 70-80% gọi LLM.
- **Discovery thông minh**: list sources qua REST `/apiv2/sources`, catalog search qua MCP, fallback theo dataset token (`NYC-taxi.csv` → `NYC-taxi` → `NYC`).
- **Pick + schema**: 1 hit → bỏ qua LLM; nhiều hit → `with_structured_output(TablePick)`.
- **HITL 2 vòng**: dùng `interrupt()` của LangGraph + `Command(resume=...)`, lưu checkpoint qua `InMemorySaver`.
- **SQL gen**: Pydantic `SqlProposal`, prompt cứng "No DML", fallback `SELECT * LIMIT 10` nếu LLM lệch.
- **Execute + Finalize**: gọi MCP `RunSqlQuery`, LLM tóm tắt JSON kết quả sang tiếng Việt.
- **4 short-circuit** giảm latency cho câu hỏi đơn giản (greeting, list sources, list catalog, describe columns).
- **Gateway FastAPI**: 3 endpoint `/aichat/v1/{config,chat,chat/resume}`, pass-through PAT user → MCP (đảm bảo phân quyền do Dremio kiểm soát).
- **Stream collector**: gộp `astream(stream_mode="updates")` thành 1 state, bắt `__interrupt__` chunk.

### 2.3 Vấn đề còn tồn tại

- `InMemorySaver` mất state khi restart gateway → cần Postgres saver cho production.
- `build_graph` mỗi request tốn ~10-50ms.
- Hard-coded prompt tiếng Anh + 1 model Ollama → chưa abstract multi-provider.
- Ollama qwen3.5:4b "thinking build" có thể hang khi stream SSE → buộc `stream=False`.

### 2.4 Kế hoạch tiếp theo

- Đổi sang `PostgresSaver` để persist HITL state qua restart.
- Cache compiled graph theo `(model, tools_hash)`.
- Hỗ trợ streaming token về UI bằng SSE.
- Tách prompt sang file YAML/i18n để dễ tinh chỉnh.

> Chi tiết kỹ thuật + Mermaid: xem `BAO-CAO-BACKEND-LANGGRAPH.md`, `langgraph-state-graph.svg`, `langgraph-sequence-flow.svg`.

---

## 3. Tính năng Autonomous Reflection

### 3.1 Mục tiêu

Tự động **đề xuất `dimension` / `measure`** cho Dremio Reflection (kỹ thuật materialized view tăng tốc query 10-100×) dựa trên AI thay vì người dùng tự đoán.

### 3.2 Công việc đã thực hiện

**Hai phần code:**

| Phần | Vị trí | Mô tả |
|------|--------|-------|
| Python ML service | `services/autonomous-reflection/` | FastAPI + sentence-transformers, 3 endpoint |
| Java client | `services/accelerator/.../analysis/` | `AutonomousReflectionClient.java`, `AutonomousReflectionIngestTask.java` |

**Stack:** FastAPI 0.103, sentence-transformers (`all-MiniLM-L6-v2`), joblib, scikit-learn / Jackson, `SchedulerService.asClusteredSingleton`.

**Kiến trúc 2 luồng** (xem `autonomous-reflection-architecture.svg`):

| Luồng | Trigger | Endpoint Python |
|-------|---------|-----------------|
| **Inference (đề xuất)** | Khi `ReflectionSuggester.getAggReflections()` chạy | `POST /predict/schema` |
| **Ingest (học)** | Định kỳ 5 phút trên coordinator-master | `POST /knowledge/ingest` |

**Tính năng đã hoàn thành (Cái này để em viết):**

### 3.3 Khó khăn

- **Regex SQL parser** không xử lý subquery / CTE / alias lồng → nên thay bằng Calcite parser của Dremio.
- **In-memory `_processed_batches`** mất idempotency 5 phút đầu sau restart.
- **Threshold cosine 0.7 hard-code** — chưa cross-validate.
- `all-MiniLM-L6-v2` chỉ tốt với tên cột tiếng Anh.

### 3.4 Kế hoạch tiếp theo

- Thay regex SQL parser → Calcite parser (Dremio đã có sẵn).
- Persist `_processed_batches` ra Redis/file.
- Đọc URL service từ `DremioConfig` thay vì hard-code.
- Thêm circuit breaker + retry cho Java client.
- Thử model embedding multilingual cho tên cột tiếng Việt.

> Chi tiết kỹ thuật + Mermaid: xem `BAO-CAO-AUTONOMOUS-REFLECTION.md`, `autonomous-reflection-architecture.svg`, `autonomous-reflection-ingest-sequence.svg`.

---

## 4. Tổng kết tháng

### 4.1 Trạng thái tổng thể

| Hạng mục | Trạng thái | Ghi chú |
|----------|-----------|---------|
| UI Chatbot | **Hoàn thành cơ bản** | Cần refactor + streaming |
| LangGraph backend | **Hoàn thành cơ bản** | Cần Postgres saver cho prod |
| Autonomous Reflection | **Hoàn thành core + ingest** | Cần thay regex SQL parser |
| Build dự án | ✅ `mvn install -DskipTests` thành công | 41:54 min, 159 module |

### 4.2 Ưu tiên tháng tiếp theo

1. **Refactor UI** thành sub-component + SSE streaming.
2. **Postgres checkpointer** cho LangGraph.
3. **Calcite parser** thay regex trong Autonomous Reflection.
4. **Tích hợp end-to-end test**: UI → Gateway → MCP → Dremio thực.
5. **Prometheus metrics** cho cả 3 service.
