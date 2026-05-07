# Triển khai tính năng Autonomous Reflection Ingest

Tài liệu này mô tả cách kích hoạt luồng **Dremio → autonomous-reflection** sau khi code đã được merge.

---

## Tổng quan luồng

```
[Dremio coordinator]
  └── ReflectionServiceImpl.start()
        └── AutonomousReflectionIngestTask (mỗi 5 phút, clustered singleton)
              ├── đọc JobsService (lịch sử query)
              ├── trích xuất cột từ SQL
              └── POST /knowledge/ingest

[autonomous-reflection FastAPI]
  └── POST /knowledge/ingest
        ├── xác thực X-AR-Ingest-Token
        ├── kiểm tra batchId trùng (idempotent)
        └── cập nhật ReflectionBrain.knowledge_base
              └── dim_score / mea_score → ảnh hưởng predict tiếp theo
```

---

## Danh sách file đã thêm / sửa

| File | Loại | Nội dung |
|------|------|----------|
| `services/autonomous-reflection/ingest_models.py` | Mới | Pydantic schemas: `IngestRequest`, `DatasetUsage`, `ColumnUsageCounts`, `IngestResponse` |
| `services/autonomous-reflection/ingest_service.py` | Mới | Logic ingest: kiểm tra duplicate batchId, cập nhật knowledge, rebuild embeddings |
| `services/autonomous-reflection/main.py` | Sửa | Thêm `POST /knowledge/ingest`, auth token, mở rộng `/health` |
| `services/autonomous-reflection/tests/test_ingest.py` | Mới | 7 test cases: 401, duplicate, 200, 422, 503, predict vẫn hoạt động |
| `services/accelerator/.../AutonomousReflectionIngestTask.java` | Mới | Runnable: đọc job, parse SQL, POST ingest định kỳ với clustered singleton |
| `services/accelerator/.../ReflectionServiceImpl.java` | Sửa | Gọi `startAutonomousReflectionIngestTask()` trong `start()` khi `isMaster` |
| `common/legacy/.../DremioConfig.java` | Sửa | 4 hằng cấu hình mới `AUTONOMOUS_REFLECTION_INGEST_*` |
| `common/legacy/.../dremio-reference.conf` | Sửa | Section `services.autonomous-reflection.ingest` với defaults |

---

## Checklist triển khai

### Bước 1 — Build Dremio

```bash
cd /home/djuybu/thesis

# Build nhanh chỉ các module đã sửa
mvn -pl common/legacy,services/accelerator -am -DskipTests install

# Hoặc build toàn bộ (lần đầu hoặc khi không chắc dependency)
mvn install -DskipTests
```

> **Lưu ý:** Nếu build lỗi do checkstyle/findbugs, thêm `-Dcheckstyle.skip -Dfindbugs.skip`.

---

### Bước 2 — Cấu hình Dremio

Mở file `dremio.conf` của môi trường (ví dụ `distribution/resources/src/main/resources/conf/dremio.conf` hoặc `/opt/dremio/conf/dremio.conf` trên server), thêm block:

```hocon
services.autonomous-reflection.ingest {
  enabled: true
  url: "http://localhost:8000/knowledge/ingest"
  token: "THAY_BANG_SECRET_THAT"
  interval_minutes: 5
}
```

> Mặc định trong `dremio-reference.conf` là `enabled: false` — phải override thành `true` mới chạy.

---

### Bước 3 — Cấu hình Python service

Đặt biến môi trường trước khi khởi động uvicorn:

```bash
export INGEST_SHARED_SECRET="THAY_BANG_SECRET_THAT"   # phải khớp với token ở trên
export MODEL_PATH="reflection_brain.pkl"
```

Nếu dùng file `.env` (Docker Compose hoặc systemd):

```env
INGEST_SHARED_SECRET=THAY_BANG_SECRET_THAT
MODEL_PATH=reflection_brain.pkl
```

> Nếu `INGEST_SHARED_SECRET` để trống, endpoint `/knowledge/ingest` **không yêu cầu token** (chỉ dùng cho môi trường nội bộ/test).

---

### Bước 4 — Khởi động Python service

```bash
cd /home/djuybu/thesis/services/autonomous-reflection

# Kích hoạt virtualenv
source .venv/bin/activate

# Chạy (production)
uvicorn main:app --host 0.0.0.0 --port 8000

# Hoặc với reload (development)
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Kiểm tra service đang chạy:

```bash
curl http://localhost:8000/health
```

Kết quả mong đợi:

```json
{
  "status": "up",
  "model_loaded": true,
  "last_ingest_batch_id": null
}
```

---

### Bước 5 — Khởi động Dremio

Khởi động Dremio như bình thường. Task ingest sẽ tự schedule sau `interval_minutes` đầu tiên.

---

### Bước 6 — Xác nhận hoạt động

**Kiểm tra log Dremio** (tìm dòng khởi động và ingest thành công):

```bash
grep -i "autonomous.reflection" /var/log/dremio/server.log
```

Kết quả mong đợi trong log:

```
INFO  - Autonomous reflection ingest task started (url=http://localhost:8000/knowledge/ingest, interval=5min)
INFO  - Ingest POST returned 200 for window [1715000000000, 1715000300000]
```

**Kiểm tra health Python sau ingest đầu tiên:**

```bash
curl http://localhost:8000/health
```

```json
{
  "status": "up",
  "model_loaded": true,
  "last_ingest_batch_id": "uuid-của-batch-đầu-tiên"
}
```

**Test thủ công endpoint ingest:**

```bash
curl -X POST http://localhost:8000/knowledge/ingest \
  -H "Content-Type: application/json" \
  -H "X-AR-Ingest-Token: THAY_BANG_SECRET_THAT" \
  -d '{
    "batchId": "manual-test-001",
    "windowStartEpochMs": 1715000000000,
    "windowEndEpochMs": 1715000300000,
    "datasets": [{
      "datasetPath": ["Sales", "orders"],
      "columnUsage": [
        {"column": "order_id", "projectionCount": 50, "filterCount": 10, "groupByCount": 5, "aggregateCount": 0},
        {"column": "revenue", "projectionCount": 20, "filterCount": 0, "groupByCount": 0, "aggregateCount": 40}
      ]
    }]
  }'
```

Kết quả mong đợi:

```json
{
  "accepted": true,
  "datasetsProcessed": 1,
  "columnsUpdated": 2,
  "skippedDuplicate": false
}
```

---

## Chạy test tự động

```bash
cd /home/djuybu/thesis/services/autonomous-reflection
source .venv/bin/activate

# Chạy tất cả test (bao gồm test ingest mới)
python -m pytest tests/test_ingest.py tests/test_simulation.py -v

# Kết quả mong đợi: 11 passed
```

---

## Xử lý sự cố

| Triệu chứng | Nguyên nhân | Giải pháp |
|-------------|-------------|-----------|
| Log Dremio không thấy "ingest task started" | `enabled: false` hoặc không phải master node | Kiểm tra `dremio.conf`, đảm bảo node là master/coordinator |
| HTTP 401 từ Python | Token sai hoặc thiếu header | Kiểm tra `INGEST_SHARED_SECRET` == `token` trong `dremio.conf` |
| HTTP 503 từ Python | Model chưa load (file pkl không tồn tại) | Chạy training trước, đảm bảo `MODEL_PATH` trỏ đúng file |
| HTTP 409/`skippedDuplicate: true` | batchId gửi trùng | Bình thường khi retry; không phải lỗi |
| Python service không nhận request | Port 8000 bị chặn hoặc chưa start | Kiểm tra firewall, `netstat -tlnp | grep 8000` |

---

## Biến môi trường Python service

| Biến | Bắt buộc | Mô tả |
|------|----------|-------|
| `INGEST_SHARED_SECRET` | Khuyến nghị | Token xác thực ingest. Để trống = không xác thực |
| `MODEL_PATH` | Không (có default) | Đường dẫn file pkl. Mặc định: `reflection_brain.pkl` |

## Cấu hình Dremio (`dremio.conf`)

| Key | Mặc định | Mô tả |
|-----|----------|-------|
| `services.autonomous-reflection.ingest.enabled` | `false` | Bật/tắt task |
| `services.autonomous-reflection.ingest.url` | `http://localhost:8000/knowledge/ingest` | URL endpoint ingest |
| `services.autonomous-reflection.ingest.token` | `""` | Token gửi kèm header `X-AR-Ingest-Token` |
| `services.autonomous-reflection.ingest.interval_minutes` | `5` | Tần suất chạy (phút) |
