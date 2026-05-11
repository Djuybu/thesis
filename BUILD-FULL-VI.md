# Hướng dẫn build toàn bộ dự án Dremio (OSS) + công cụ liên quan

Tài liệu này tóm tắt cách **build cả monorepo Dremio** và các thành phần phụ trợ trong repo thesis (Dremio SQL Agent gateway, dremio-mcp). Phần lõi Dremio dựa trên [README.md](README.md) chính thức.

---

## 1. Yêu cầu môi trường (build Dremio đầy đủ)

| Thành phần | Ghi chú |
|------------|---------|
| **JDK 21** | Bắt buộc làm JDK mặc định (`java -version`, `JAVA_HOME`). |
| **JDK 11 & 17** | Khai báo trong **`~/.m2/toolchains.xml`** — Maven dùng khi chạy test / một số module. Build **`-DskipTests`** vẫn nên có file toolchains để tránh lỗi toolchain ở bước compile một số nơi. |
| **Maven** | Tuỳ chọn nếu dùng **`./mvnw`** (wrapper đi kèm repo). |
| **RAM / đĩa** | Full `install` không test vẫn tốn nhiều phút và vài GB `~/.m2` + `target/`. |

Mẫu `~/.m2/toolchains.xml` (đổi `FULL_PATH_...` thật):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides><version>11</version><vendor>sun</vendor></provides>
    <configuration><jdkHome>FULL_PATH_TO_JAVA_11</jdkHome></configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides><version>17</version><vendor>sun</vendor></provides>
    <configuration><jdkHome>FULL_PATH_TO_JAVA_17</jdkHome></configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides><version>21</version><vendor>sun</vendor></provides>
    <configuration><jdkHome>FULL_PATH_TO_JAVA_21</jdkHome></configuration>
  </toolchain>
</toolchains>
```

---

## 2. Build **toàn bộ** Dremio OSS (một lệnh)

Từ thư mục gốc repo (có `pom.xml`, `mvnw`):

```bash
cd /path/to/thesis   # hoặc dremio-oss
./mvnw clean install -DskipTests
```

- **`clean install`**: biên dịch toàn bộ module (`build-tools`, `dac`, `sabot`, `distribution`, `ui`, `tools`, …) và cài bản build vào local Maven repo.
- **`-DskipTests`**: bỏ qua test — build nhanh hơn rất nhiều; bỏ cờ này nếu bạn cần chạy full test (lâu).

### Chỉ dependency giấy phép OSS

```bash
./mvnw clean install -DskipTests -Ddremio.oss-only=true
```

### Sau khi build xong — chạy nhanh

Theo README:

```bash
distribution/server/target/dremio-community-*/dremio-community-*/bin/dremio start
```

Hoặc (dev, user mặc định):

```bash
./mvnw compile exec:exec -pl dac/daemon
```

UI: **http://localhost:9047**

---

## 3. Cấu trúc build (để biết "toàn bộ" gồm gì)

Các **module Maven** chính trong `pom.xml` gốc:

| Module | Vai trò ngắn gọn |
|--------|-------------------|
| `build-tools` | Công cụ build (gồm `dremio-errorprone` dùng khi compile) |
| `client`, `common`, `connector`, `protocol`, … | Thư viện lõi |
| `dac` | Analyst Center — backend + UI liên quan |
| `sabot` | Query engine |
| `plugins` | Connector / plugin |
| `services`, `provision`, … | Dịch vụ hỗ trợ |
| `ui` | Frontend OSS |
| `distribution` | Đóng gói tarball / server |
| `tools` | Công cụ phụ trợ (attach-tool, testcontainers, …) |

---

## 4. Dremio SQL Agent Gateway (thay thế aichatbot-plugin)

Hướng dẫn chạy từng bước (tiếng Việt): **[tools/dremio-mcp/docs/RUN-VI.md](tools/dremio-mcp/docs/RUN-VI.md)**.

Gateway mới nằm trong **`tools/dremio-mcp`** (Python, không phải Maven module).

### Cài đặt

```bash
cd tools/dremio-mcp
python3 -m venv .venv && source .venv/bin/activate
pip install -e .
```

### Chạy gateway

```bash
# Cần Ollama chạy sẵn (mặc định port 11434)
# Cần dremio-mcp server chạy ở port 8080
dremio-sql-agent
```

Hoặc trực tiếp:

```bash
python -m dremioai.gateway.app
```

Gateway mặc định chạy tại **http://127.0.0.1:9292** (cấu hình qua `GATEWAY_HOST`, `GATEWAY_PORT`).

### Biến môi trường chính

| Biến | Mặc định | Ghi chú |
|------|----------|---------|
| `OLLAMA_MODEL` | `qwen3.5:4b` | Ollama model id |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | Ollama API |
| `DREMIO_MCP_URL` | `http://127.0.0.1:8080/mcp/` | Dremio MCP HTTP endpoint |
| `GATEWAY_HOST` | `127.0.0.1` | Gateway bind host |
| `GATEWAY_PORT` | `9292` | Gateway bind port |

### API Endpoints

| Method | Path | Mô tả |
|--------|------|-------|
| `GET` | `/aichat/health` | Health check |
| `GET` | `/aichat/v1/config` | Service config |
| `POST` | `/aichat/v1/chat` | Bắt đầu chat thread (HITL) |
| `POST` | `/aichat/v1/chat/resume` | Resume sau HITL interrupt |

Chi tiết API: [tools/dremio-mcp/docs/api-spec.md](tools/dremio-mcp/docs/api-spec.md).

---

## 5. **dremio-mcp** server

Theo [tools/dremio-mcp/README.md](tools/dremio-mcp/README.md) — thường **uv** + `dremio-mcp-server`.

Cấu hình: [tools/dremio-mcp/local/mcp-oss.yaml](tools/dremio-mcp/local/mcp-oss.yaml).

---

## 6. Triển khai end-to-end

Thứ tự khởi động:
1. **Dremio** (port 9047)
2. **Ollama** (port 11434) — `ollama serve` + `ollama pull qwen3.5:4b`
3. **dremio-mcp server** (port 8080) — `cd tools/dremio-mcp && uv run dremio-mcp-server run -c local/mcp-oss.yaml --enable-streaming-http --port 8080`
4. **SQL Agent gateway** (port 9292) — `dremio-sql-agent`

Cấu hình Dremio: `conf/dremio.conf` → `services.coordinator.web.aichatbot.plugin.base_url = "http://127.0.0.1:9292"`

---

## 7. Sự cố thường gặp

| Hiện tượng | Hướng xử lý |
|------------|-------------|
| `apt` / `dpkg` lock (`unattended-upgrades`) | Đợi cập nhật xong; không kill giữa chừng. |
| Build quá lâu / hết RAM | Dùng máy RAM đủ; hoặc chỉ `-pl` module cần thiết cho tác vụ hiện tại. |
| Sai JDK | Đảm bảo `JAVA_HOME` trỏ JDK **21** khi chạy Maven cho repo này. |
| MCP tools load timeout | Tăng `DREMIO_MCP_TIMEOUT_SECONDS`; kiểm tra dremio-mcp server đang chạy. |
| Gateway 502 | Kiểm tra Dremio token hợp lệ, dremio-mcp server đang chạy, Ollama sẵn sàng. |

---

## 8. Tham chiếu nhanh

- Build & chạy chính thức: [README.md](README.md) — mục *Quickstart: How to build and run Dremio*.
- API spec: [tools/dremio-mcp/docs/api-spec.md](tools/dremio-mcp/docs/api-spec.md).
- Kiến trúc MCP: [tools/dremio-mcp/docs/architecture.md](tools/dremio-mcp/docs/architecture.md).
- Docker distribution: [distribution/docker/README.md](distribution/docker/README.md).
