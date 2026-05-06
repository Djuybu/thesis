# Hướng dẫn build toàn bộ dự án Dremio (OSS) + công cụ liên quan

Tài liệu này tóm tắt cách **build cả monorepo Dremio** và các thành phần phụ trợ trong repo thesis (plugin chatbot, gateway LangChain, dremio-mcp). Phần lõi Dremio dựa trên [README.md](README.md) chính thức.

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

Thư mục phân phối sẽ dạng `dremio-oss-{version}` thay vì bản “community” đầy đủ driver (xem [README.md](README.md) mục OSS Only).

### Sau khi build xong — chạy nhanh

Theo README:

```bash
# Gói server (đường dẫn chính xác phụ thuộc version trong distribution/server/target)
distribution/server/target/dremio-community-*/dremio-community-*/bin/dremio start
```

Hoặc (dev, user mặc định):

```bash
./mvnw compile exec:exec -pl dac/daemon
```

UI: **http://localhost:9047**

---

## 3. Cấu trúc build (để biết “toàn bộ” gồm gì)

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
| `tools` | Công cụ — gồm **`tools/aichatbot-plugin`** (plugin AI chatbot) |

Build full `install` sẽ quét hết các module này (trừ khi bạn giới hạn `-pl`).

---

## 4. Chỉ build **plugin AI Chatbot** (nhanh, để test chat)

Không cần full Dremio nếu bạn chỉ cần JAR plugin:

```bash
./mvnw -pl tools/aichatbot-plugin -DskipTests -Derrorprone.skip=true package
```

- **`-Derrorprone.skip=true`**: cần khi build **chỉ** plugin trên máy không có artifact nội bộ `dremio-errorprone` đã `install` từ cả tree (xem [tools/aichatbot-plugin/build-for-local-test.sh](tools/aichatbot-plugin/build-for-local-test.sh)).

Nếu bạn đã **`./mvnw clean install -DskipTests`** full trước đó, `build-tools` đã vào `~/.m2` — có thể thử bỏ `errorprone.skip` khi build lại plugin.

JAR: `tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar`

---

## 5. **Python** — LangChain gateway & dremio-mcp (ngoài Maven)

Không nằm trong `mvnw install`:

| Thành phần | Cách chuẩn bị |
|------------|----------------|
| **langchain-gateway** | Python 3.11+, `venv`, `pip install -r tools/aichatbot-plugin/langchain-gateway/requirements.txt`. Nếu thiếu `python3-venv` trên Ubuntu/WSL: `sudo apt install python3.12-venv` (chờ hết `unattended-upgrades` nếu bị lock), hoặc dùng **uv** để tạo venv không cần apt. |
| **dremio-mcp** | Theo [tools/dremio-mcp/README.md](tools/dremio-mcp/README.md) — thường **uv** + `dremio-mcp-server`. |

Triển khai end-to-end (OSS + plugin + MCP + Ollama + gateway): [tools/aichatbot-plugin/DEPLOYMENT.md](tools/aichatbot-plugin/DEPLOYMENT.md).  
Chỉ chatbot: [tools/aichatbot-plugin/CHATBOT-DEPLOY.md](tools/aichatbot-plugin/CHATBOT-DEPLOY.md).

---

## 6. Script gợi ý trong repo

- [tools/aichatbot-plugin/build-for-local-test.sh](tools/aichatbot-plugin/build-for-local-test.sh) — build plugin (có `errorprone.skip`) + thử tạo venv gateway.

---

## 7. Sự cố thường gặp

| Hiện tượng | Hướng xử lý |
|------------|-------------|
| `Could not find artifact ... dremio-errorprone` khi build **chỉ** plugin | Thêm `-Derrorprone.skip=true` **hoặc** chạy full install trước để cài `build-tools`. |
| `apt` / `dpkg` lock (`unattended-upgrades`) | Đợi cập nhật xong; không kill giữa chừng. |
| Build quá lâu / hết RAM | Dùng máy RAM đủ; hoặc chỉ `-pl` module cần thiết cho tác vụ hiện tại. |
| Sai JDK | Đảm bảo `JAVA_HOME` trỏ JDK **21** khi chạy Maven cho repo này. |

---

## 8. Tham chiếu nhanh

- Build & chạy chính thức: [README.md](README.md) — mục *Quickstart: How to build and run Dremio*.
- Docker distribution: [distribution/docker/README.md](distribution/docker/README.md).
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) (nếu có).
