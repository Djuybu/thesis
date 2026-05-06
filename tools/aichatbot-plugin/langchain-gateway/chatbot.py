"""
Mini chatbot biết schema của một dataset Dremio.

- Tự lấy schema (tên cột + kiểu) từ Dremio /api/v3/catalog/by-path.
- Câu hỏi "schema" (liệt kê / có những cột nào / kiểu dữ liệu …) -> trả lời
  deterministic từ cache, không gọi LLM, không bịa.
- Các câu hỏi aggregate đơn giản (đếm, trung bình, max, min, sum) -> tự sinh
  SQL, chạy trên Dremio, in kết quả deterministic.
- Câu hỏi khác -> gửi qua /gateway/chat của LangChain gateway.

Cấu hình qua biến môi trường:
    DREMIO_BASE_URL   default http://127.0.0.1:9047
    GATEWAY_URL       default http://127.0.0.1:9292
    DREMIO_TOKEN      bắt buộc
    TABLE_PATH        default @admin/green_tripdata_2025-01  (slash-separated)
    OLLAMA_MODEL      default qwen2.5:3b

Cách dùng:
    export DREMIO_TOKEN=...
    python chatbot.py                 # interactive
    python chatbot.py "Có cột nào?"   # 1 câu rồi thoát
    python chatbot.py ":cols"         # liệt kê cột deterministic
    python chatbot.py ":sql SELECT 1" # chạy SQL bất kỳ qua Dremio
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request


def env(name: str, default: str | None = None) -> str | None:
    v = os.environ.get(name)
    return v if v not in (None, "") else default


def http_json(method: str, url: str, headers: dict, body: dict | None = None,
              timeout: int = 600) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw) if raw else {}


def fetch_schema(dremio_base: str, token: str,
                 table_path: str) -> tuple[str, list[dict]]:
    parts = [p for p in table_path.strip("/").split("/") if p]
    encoded = "/".join(urllib.parse.quote(p, safe="") for p in parts)
    url = f"{dremio_base.rstrip('/')}/api/v3/catalog/by-path/{encoded}"
    headers = {"Authorization": f"_dremio{token}", "Accept": "application/json"}
    data = http_json("GET", url, headers)
    fields = data.get("fields") or []
    cols = []
    for f in fields:
        t = f.get("type")
        tname = t.get("name") if isinstance(t, dict) else str(t)
        cols.append({"name": f.get("name"), "type": tname})
    fqn = ".".join(f'"{p}"' for p in parts)
    return fqn, cols


def run_sql(dremio_base: str, token: str, sql: str,
            poll_interval: float = 0.7, timeout: int = 90) -> dict:
    headers = {
        "Authorization": f"_dremio{token}",
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    submit = http_json(
        "POST", f"{dremio_base.rstrip('/')}/api/v3/sql", headers, {"sql": sql})
    job_id = submit["id"]
    deadline = time.time() + timeout
    while True:
        st = http_json(
            "GET", f"{dremio_base.rstrip('/')}/api/v3/job/{job_id}", headers)
        state = st.get("jobState")
        if state in ("COMPLETED",):
            break
        if state in ("FAILED", "CANCELED"):
            raise RuntimeError(f"SQL {state}: {st.get('errorMessage')}")
        if time.time() > deadline:
            raise TimeoutError(f"SQL timeout sau {timeout}s")
        time.sleep(poll_interval)
    return http_json(
        "GET", f"{dremio_base.rstrip('/')}/api/v3/job/{job_id}/results",
        headers)


SCHEMA_KEYWORDS_VN = (
    "cột", "kiểu", "schema", "trường", "field", "danh sách cột",
    "liệt kê cột",
)
SCHEMA_KEYWORDS_EN = ("column", "columns", "schema", "fields")


def is_schema_question(q: str) -> bool:
    s = q.lower()
    return any(k in s for k in SCHEMA_KEYWORDS_VN) or \
        any(k in s for k in SCHEMA_KEYWORDS_EN)


def answer_schema_local(table_label: str, cols: list[dict]) -> str:
    out = [f"Bảng {table_label} có {len(cols)} cột:"]
    for c in cols:
        out.append(f"- {c['name']}: {c['type']}")
    return "\n".join(out)


_COUNT_PATTERNS = [
    r"\bbao nhiêu\b.*\b(chuyến|dòng|hàng|bản ghi|bản|record|row|rows)\b",
    r"\b(số|tổng|đếm).*\b(chuyến|dòng|hàng|bản ghi|record|row|rows)\b",
    r"\bcount\b\s*\(?\s*\*\s*\)?",
]


def _fmt_int(v) -> str:
    try:
        return f"{int(v):,}".replace(",", ".")
    except Exception:
        return str(v)


def _fmt_decimal(v, ndigits: int = 2) -> str:
    try:
        return f"{float(v):,.{ndigits}f}".replace(",", "X").replace(
            ".", ",").replace("X", ".")
    except Exception:
        return str(v)


def detect_simple_sql(question: str, cols: list[dict],
                      fqn: str) -> tuple[str, str] | None:
    """Phát hiện câu hỏi aggregate đơn giản → trả (sql, prefix tiếng Việt)."""
    q = question.lower()

    for pat in _COUNT_PATTERNS:
        if re.search(pat, q):
            return f"SELECT COUNT(*) AS total FROM {fqn}", "Số dòng"

    numeric_types = {"DOUBLE", "FLOAT", "DECIMAL", "BIGINT", "INTEGER"}
    numeric_cols = [c["name"] for c in cols if c["type"] in numeric_types]

    def find_column(text: str, only_numeric: bool = False) -> str | None:
        candidates = numeric_cols if only_numeric else [c["name"] for c in cols]
        for c in sorted(candidates, key=len, reverse=True):
            if c.lower() in text:
                return c
        return None

    if any(k in q for k in ("trung bình", "average", " avg")):
        col = find_column(q, only_numeric=True)
        if col:
            return (f'SELECT AVG("{col}") AS v FROM {fqn}',
                    f"Trung bình {col}")
    if any(k in q for k in ("tổng cộng", " sum ", "tổng của", "tổng ")):
        col = find_column(q, only_numeric=True)
        if col:
            return f'SELECT SUM("{col}") AS v FROM {fqn}', f"Tổng {col}"
    if any(k in q for k in ("lớn nhất", "max ", "cao nhất", "maximum")):
        col = find_column(q, only_numeric=True)
        if col:
            return (f'SELECT MAX("{col}") AS v FROM {fqn}',
                    f"Giá trị lớn nhất của {col}")
    if any(k in q for k in ("nhỏ nhất", "min ", "thấp nhất", "minimum")):
        col = find_column(q, only_numeric=True)
        if col:
            return (f'SELECT MIN("{col}") AS v FROM {fqn}',
                    f"Giá trị nhỏ nhất của {col}")

    return None


def format_aggregate_answer(prefix: str, value) -> str:
    if isinstance(value, bool):
        return f"{prefix}: {value}"
    if isinstance(value, int):
        return f"{prefix}: {_fmt_int(value)}"
    if isinstance(value, float):
        return f"{prefix}: {_fmt_decimal(value, 2)}"
    if isinstance(value, str) and value.replace("-", "").isdigit():
        return f"{prefix}: {_fmt_int(value)}"
    return f"{prefix}: {value}"


def format_sql_result(res: dict) -> str:
    rows = res.get("rows") or []
    if not rows:
        return "(không có hàng)"
    if len(rows) == 1 and len(rows[0]) == 1:
        return str(next(iter(rows[0].values())))
    return json.dumps(rows, ensure_ascii=False, indent=2)


def ask_gateway(gateway_url: str, token: str, model: str, fqn: str,
                cols: list[dict], session_id: str, question: str) -> str:
    user_context = json.dumps({
        "table_fqn": fqn,
        "columns": cols,
        "hints": [
            f"Bảng đang dùng: {fqn} (đã biết schema, không cần khám phá lại).",
            "Khi cần số liệu, gọi tool RunSqlQuery với SQL đúng tên cột.",
            "Trả lời bằng tiếng Việt, ngắn gọn, không kể quá trình gọi tool.",
        ],
    }, ensure_ascii=False)
    body = {
        "message": question,
        "session_id": session_id,
        "user_id": "admin",
        "model": model,
        "temperature": 0,
        "strict_grounding": False,
        "data_query_workflow": False,
        "user_context": user_context,
    }
    headers = {"Authorization": f"Bearer {token}",
               "Content-Type": "application/json"}
    data = http_json(
        "POST", f"{gateway_url.rstrip('/')}/gateway/chat", headers, body)
    return data.get("answer") or "(không có trả lời)"


def handle_question(question: str, *, dremio_base: str, gateway_url: str,
                    token: str, model: str, fqn: str, cols: list[dict],
                    session_id: str, table_label: str) -> str:
    q = question.strip()

    if q.startswith(":cols"):
        return answer_schema_local(table_label, cols)
    if q.startswith(":sql "):
        sql = q[len(":sql "):].strip()
        return format_sql_result(run_sql(dremio_base, token, sql))

    if is_schema_question(q):
        return answer_schema_local(table_label, cols)

    detected = detect_simple_sql(q, cols, fqn)
    if detected:
        sql, prefix = detected
        try:
            res = run_sql(dremio_base, token, sql)
            rows = res.get("rows") or []
            if rows and len(rows[0]) == 1:
                return format_aggregate_answer(
                    prefix, next(iter(rows[0].values())))
            return format_sql_result(res)
        except Exception as e:
            return f"(không chạy được SQL: {e})"

    return ask_gateway(gateway_url, token, model, fqn, cols, session_id, q)


def main() -> int:
    dremio_base = env("DREMIO_BASE_URL", "http://127.0.0.1:9047")
    gateway_url = env("GATEWAY_URL", "http://127.0.0.1:9292")
    token = env("DREMIO_TOKEN")
    table_path = env("TABLE_PATH", "@admin/green_tripdata_2025-01")
    model = env("OLLAMA_MODEL", "qwen2.5:3b")

    if not token:
        print("Thiếu DREMIO_TOKEN.\n  export DREMIO_TOKEN=...",
              file=sys.stderr)
        return 2

    print(f"[loading schema] {table_path} ...", file=sys.stderr)
    try:
        fqn, cols = fetch_schema(dremio_base, token, table_path)
    except Exception as e:
        print(f"Không lấy được schema: {e}", file=sys.stderr)
        return 1
    print(f"[ok] {len(cols)} cột — bảng FQN: {fqn}", file=sys.stderr)

    table_label = "/".join(table_path.strip("/").split("/"))
    session_id = f"cli-{os.getpid()}"

    args = sys.argv[1:]
    if args:
        q = " ".join(args).strip()
        print(handle_question(
            q, dremio_base=dremio_base, gateway_url=gateway_url, token=token,
            model=model, fqn=fqn, cols=cols, session_id=session_id,
            table_label=table_label))
        return 0

    print("Gõ câu hỏi (Ctrl+D thoát).  Lệnh đặc biệt: ':cols', ':sql <SQL>'",
          file=sys.stderr)
    while True:
        try:
            q = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print(file=sys.stderr)
            return 0
        if not q:
            continue
        try:
            print(handle_question(
                q, dremio_base=dremio_base, gateway_url=gateway_url,
                token=token, model=model, fqn=fqn, cols=cols,
                session_id=session_id, table_label=table_label))
        except Exception as e:
            print(f"[error] {e}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
