from __future__ import annotations

import asyncio
import json
import os
import re
import time
import unicodedata
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage

from dremioai.agent.nodes.common import (
    TablePick,
    _agent_trace_llm_content_on,
    _json_compact,
    _preview,
    _trace,
    _trace_verbose,
    agent_debug_ndjson,
    structured_llm_timeout_seconds,
)
from dremioai.agent.state import AgentState
from dremioai.agent.tools import get_schema_tool, get_search_tool, get_sql_tool
from dremioai.gateway.config import dremio_rest_base_url

_SOURCES_LIST_RX = re.compile(
    r"data\s*sources?|\bdatasource\b|nguồn\s*dữ\s*liệu|nguon\s*du\s*lieu|"
    r"\bsources?\b|kết\s*nối\s*dữ\s*liệu",
    re.I,
)


def user_asks_for_dremio_sources_list(q: str) -> bool:
    """Heuristic: user wants Dremio connections / top-level schemas, not dataset search."""
    return bool(_SOURCES_LIST_RX.search(q or ""))


# Listing / inventory — user wants many tables described, not one table picked for SQL.
_MULTI_TABLE_CATALOG_RX = re.compile(
    r"liệt\s*kê|danh\s*sách|tất\s*cả\s*bảng|các\s*bảng|mọi\s*bảng|"
    r"những\s+bảng|bảng\s+(?:và|hay)|"
    r"thư\s*mục\s*và\s*bảng|thư\s*mục|các\s*thư\s*mục|"
    r"mô\s*tả\s+các|mô\s*tả\s+các\s+bảng|"
    r"\blist\s+(?:all\s+)?(?:tables?|datasets?|views?)\b|"
    r"\bshow\s+(?:all\s+)?(?:tables?|datasets?)\b|"
    r"\bwhat\s+(?:tables?|datasets?)\b|"
    r"\bcatalog\b|\binventory\b|khám\s*phá\s*dữ\s*liệu",
    re.I,
)

_ANALYTICAL_SINGLE_FLOW_RX = re.compile(
    r"\b(?:join|inner\s+join|left\s+join|right\s+join|full\s+join)\b|"
    r"\b(?:so\s+sánh|compare|correlat)\b|"
    r"\b(?:aggregate|group\s+by|plot|chart|forecast)\b|"
    r"\b(?:viết|tao)\s+(?:một\s+)?(?:câu\s+)?sql\b|"
    r"\bwrite\s+(?:a\s+)?sql\b",
    re.I,
)


_SCHEMA_DESCRIBE_RX = re.compile(
    r"mô\s*tả(?:[^.!?\n]{0,40}?)?\s+(?:các\s+|những\s+|các\s+loại\s+)?(?:trường|cột|field|column|schema)|"
    r"liệt\s*kê\s+(?:các\s+)?(?:trường|cột|field|column)|"
    r"các\s+(?:trường|cột)\s+(?:dữ\s+liệu\s+)?(?:của|trong)\b|"
    r"\b(?:describe|show|list)\s+(?:the\s+)?(?:schema|columns?|fields?|table\s+structure)\b|"
    r"cấu\s*trúc\s+(?:bảng|dữ\s+liệu)|"
    r"có\s+(?:những\s+|các\s+)?(?:trường|cột)\s+(?:gì|nào)",
    re.I,
)

_ANALYTICAL_SINGLE_FLOW_ASCII_RX = re.compile(
    r"\b(?:join|inner\s+join|left\s+join|right\s+join|full\s+join)\b|"
    r"\b(?:so\s+sanh|compare|correlat)\b|"
    r"\b(?:aggregate|group\s+by|plot|chart|forecast)\b|"
    r"\b(?:viet|tao)\s+(?:mot\s+)?(?:cau\s+)?sql\b|"
    r"\bwrite\s+(?:a\s+)?sql\b",
    re.I,
)

_SCHEMA_DESCRIBE_ASCII_RX = re.compile(
    r"mo\s*ta(?:[^.!?\n]{0,40}?)?\s+(?:cac\s+|nhung\s+|cac\s+loai\s+)?(?:truong|cot|field|column|schema)|"
    r"liet\s*ke\s+(?:cac\s+)?(?:truong|cot|field|column)|"
    r"(?:cac|nhung)\s+(?:truong|cot)\s+(?:du\s+lieu\s+)?(?:cua|trong)\b|"
    r"\b(?:describe|show|list)\s+(?:the\s+)?(?:schema|columns?|fields?|table\s+structure)\b|"
    r"cau\s*truc\s+(?:bang|du\s+lieu)|"
    r"co\s+(?:nhung\s+|cac\s+)?(?:truong|cot)\s+(?:gi|nao|trong)",
    re.I,
)


def _ascii_intent_text(msg: str) -> str:
    text = unicodedata.normalize("NFKD", msg or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.replace("\u0111", "d").replace("\u0110", "D")
    return text.replace("đ", "d").replace("Đ", "D").lower()


def user_asks_for_schema_description(q: str) -> bool:
    """True when the user wants a column/field description of ONE table (no SQL needed)."""
    s = q or ""
    ascii_s = _ascii_intent_text(s)
    if not s.strip():
        return False
    if _ANALYTICAL_SINGLE_FLOW_RX.search(s) or _ANALYTICAL_SINGLE_FLOW_ASCII_RX.search(ascii_s):
        return False
    return bool(_SCHEMA_DESCRIBE_RX.search(s) or _SCHEMA_DESCRIBE_ASCII_RX.search(ascii_s))


def _format_schema_description_answer(schema_text: Any, table_fqn: str) -> str:
    """Render a friendly Vietnamese table description from GetSchemaOfTable output."""
    parsed: Any = None
    if isinstance(schema_text, str):
        try:
            parsed = json.loads(schema_text)
        except (json.JSONDecodeError, TypeError):
            parsed = None
    elif isinstance(schema_text, dict):
        parsed = schema_text

    fields: list[dict[str, Any]] = []
    extra_meta: dict[str, Any] = {}
    if isinstance(parsed, dict):
        raw_fields = parsed.get("fields") or parsed.get("Fields") or []
        if isinstance(raw_fields, list):
            fields = [f for f in raw_fields if isinstance(f, dict)]
        for k in ("type", "format", "path", "entityType"):
            if k in parsed:
                extra_meta[k] = parsed[k]

    short_name = table_fqn
    if "." in table_fqn:
        short_name = table_fqn.rsplit(".", 1)[-1].strip('"')

    if not fields:
        return (
            f"Đã lấy metadata cho bảng **`{table_fqn}`** nhưng không tìm thấy danh sách "
            "trường (`fields`). Vui lòng kiểm tra quyền truy cập trên Dremio hoặc thử "
            "đặt câu hỏi phân tích cụ thể để agent sinh SQL."
        )

    def _type_name(t: Any) -> str:
        if isinstance(t, dict):
            n = t.get("name") or t.get("type") or ""
            return str(n) if n else "?"
        return str(t) if t is not None else "?"

    fmt_info = extra_meta.get("format")
    fmt_label = ""
    if isinstance(fmt_info, dict):
        fmt_label = str(fmt_info.get("type") or "").strip()
    elif isinstance(fmt_info, str):
        fmt_label = fmt_info

    head_lines = [f"**Bảng:** `{table_fqn}`"]
    if fmt_label:
        head_lines.append(f"**Định dạng nguồn:** {fmt_label}")
    head_lines.append(f"**Số trường (cột):** {len(fields)}")
    head = "\n".join(head_lines) + "\n\n"

    table_md = (
        "| # | Tên cột | Kiểu dữ liệu | Ghi chú |\n"
        "|---|---------|--------------|---------|\n"
    )
    rows: list[str] = []
    for i, fld in enumerate(fields, start=1):
        name = fld.get("name") or "?"
        tname = _type_name(fld.get("type"))
        notes: list[str] = []
        if fld.get("isPartitioned"):
            notes.append("partitioned")
        if fld.get("isSorted"):
            notes.append("sorted")
        note_s = ", ".join(notes) if notes else ""
        rows.append(f"| {i} | `{name}` | `{tname}` | {note_s} |")

    footer = (
        "\n\n---\n"
        "**Bạn có thể hỏi tiếp (không cần biết SQL):**\n"
        f"1. *\"Xem 10 dòng đầu của `{short_name}`\"*\n"
        f"2. *\"Đếm tổng số bản ghi trong `{short_name}`\"*\n"
        f"3. *\"Đếm số bản ghi theo `<Tên cột>`\"* (ví dụ: `Category`, `DayOfWeek`...)\n"
        f"4. *\"Lọc các bản ghi có `<Tên cột>` bằng `<giá trị>`\"*\n\n"
        "Agent sẽ tự sinh truy vấn SQL và **xin xác nhận của bạn** trước khi chạy."
    )
    return head + table_md + "\n".join(rows) + footer


def user_wants_multi_table_catalog_answer(q: str) -> bool:
    """True when the user asks to enumerate or describe several tables (catalog-style).

    Set ``AGENT_MULTI_TABLE_SHORT_CIRCUIT=0`` to disable this path (always use pick/schema/SQL flow).
    """
    if os.environ.get("AGENT_MULTI_TABLE_SHORT_CIRCUIT", "1").strip().lower() in (
        "0",
        "false",
        "no",
        "off",
    ):
        return False
    s = q or ""
    if not s.strip():
        return False
    if _ANALYTICAL_SINGLE_FLOW_RX.search(s):
        return False
    return bool(_MULTI_TABLE_CATALOG_RX.search(s))


_DATASET_TOKEN_RX = re.compile(
    r"[A-Za-z][A-Za-z0-9_]*(?:[.\-_][A-Za-z0-9_]+)+"
)

_KNOWN_DATASET_EXTS = (".csv", ".json", ".parquet", ".tsv", ".xlsx", ".xls", ".jsonl")


def _extract_dataset_token_candidates(raw: str) -> list[str]:
    """Pull file/dataset-like identifiers (with dots, hyphens, underscores) from a question."""
    if not raw:
        return []
    seen: list[str] = []
    for m in _DATASET_TOKEN_RX.finditer(raw):
        tok = m.group(0).strip(".-_")
        if not tok or len(tok) < 3:
            continue
        if tok not in seen:
            seen.append(tok)
    seen.sort(key=len, reverse=True)
    return seen


def _stem_without_known_ext(token: str) -> str | None:
    low = token.lower()
    for ext in _KNOWN_DATASET_EXTS:
        if low.endswith(ext):
            stem = token[: -len(ext)]
            return stem if stem else None
    return None


def normalize_catalog_search_query(raw: str) -> str:
    """Compact natural-language prompts into short keywords for GET /catalog/search."""
    s = (raw or "").strip()
    if not s:
        return ""
    noise_patterns = (
        r"Liệt kê\s*\d*\s*",
        r"[Ll]ist\s*(the\s*)?\d*\s*",
        r"mô\s*tả\s*\d*\s*",
        r"mô\s*tả\s+",
        r"trong\s+Data\s+Source\.?",
        r"chỉ\s+trả\s+về[^.!?]{0,100}",
        r"không\s+mô\s+tả[^.!?]{0,60}",
        r"trong\s+[Dd]remio[^.!?]{0,40}",
        r"hiện\s+có\s+",
        r"only\s+return[^.!?]{0,100}",
        r"do\s+not\s+describe[^.!?]{0,80}",
        r"[`'\"]+",
    )
    for pat in noise_patterns:
        s = re.sub(pat, " ", s, flags=re.IGNORECASE)
    s = re.sub(r"\s+", " ", s).strip()
    if len(s) > 100:
        s = s[:100].rsplit(" ", 1)[0]
    if len(s) < 2:
        s = (raw or "").strip()[:80]
    return s


def _mcp_tool_output_to_dict(out: Any) -> dict[str, Any] | None:
    """LangChain MCP adapters may return a dict or a JSON string."""
    if isinstance(out, dict):
        return out
    if isinstance(out, str):
        try:
            parsed = json.loads(out)
            return parsed if isinstance(parsed, dict) else None
        except (json.JSONDecodeError, TypeError):
            return None
    return None


def _rows_from_run_sql_output(out: Any) -> tuple[list[dict[str, Any]], str | None]:
    """Parse RunSqlQuery output -> (rows, error_or_None)."""
    d = _mcp_tool_output_to_dict(out)
    if d is None:
        return [], "unparsed_tool_output"
    if d.get("error"):
        return [], str(d.get("error"))
    r = d.get("result")
    if isinstance(r, list):
        return r, None
    return [], None


def _tables_from_search_tool_output(out: Any) -> list[dict[str, Any]]:
    """Normalize SearchTableAndViews output.

    The MCP tool returns ``{"results": [ {...}, ... ]}``. LangChain adapters often
    stringify that to JSON; discovery previously only accepted a top-level JSON
    array, so ``tables_found`` stayed 0 while ``discover_result`` still had data.
    """
    if isinstance(out, list):
        return [x for x in out if isinstance(x, dict)]
    if isinstance(out, dict):
        r = out.get("results")
        if isinstance(r, list):
            return [x for x in r if isinstance(x, dict)]
        return []
    if isinstance(out, str):
        try:
            parsed = json.loads(out)
        except (json.JSONDecodeError, TypeError):
            return []
        if isinstance(parsed, list):
            return [x for x in parsed if isinstance(x, dict)]
        if isinstance(parsed, dict):
            r = parsed.get("results")
            if isinstance(r, list):
                return [x for x in r if isinstance(x, dict)]
    return []


async def _fetch_apiv2_sources(
    authorization: str, base_uri: str
) -> tuple[list[dict[str, Any]] | None, int | None, str | None]:
    """Call Dremio ``GET /apiv2/sources`` with the same Bearer the client sent to the gateway."""
    import aiohttp

    url = base_uri.rstrip("/") + "/apiv2/sources"
    try:
        timeout = aiohttp.ClientTimeout(total=90)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(
                url,
                params={"includeDatasetCount": "true"},
                headers={
                    "Authorization": authorization,
                    "Accept": "application/json",
                },
                ssl=False,
            ) as resp:
                status = resp.status
                text = await resp.text()
                if status != 200:
                    return None, status, (text or "")[:500]
                payload = json.loads(text)
                sources = payload.get("sources")
                if not isinstance(sources, list):
                    return None, status, "response_missing_sources_array"
                return sources, status, None
    except Exception as e:
        return None, None, str(e)[:500]


def _format_apiv2_source_state(st: Any) -> str | None:
    """Short human-readable status from Dremio ``state`` (often a dict with ``status``)."""
    if st is None:
        return None
    if isinstance(st, dict):
        status = st.get("status")
        if status is not None:
            return f"trạng thái: {status}"
        return None
    return str(st)


def _format_apiv2_sources_answer(sources: list[dict[str, Any]], limit: int = 40) -> str:
    lines: list[str] = []
    for i, s in enumerate(sources[:limit], start=1):
        name = s.get("name") or "?"
        cfg = s.get("config") if isinstance(s.get("config"), dict) else {}
        ctype = (cfg or {}).get("@type") or (cfg or {}).get("type") or ""
        st = s.get("state")
        line = f"{i}. **{name}**"
        if ctype:
            line += f" — `{ctype}`"
        nd = s.get("numberOfDatasets")
        if nd is not None:
            bounded = bool(s.get("datasetCountBounded"))
            if bounded:
                line += f" — ~{nd}+ dataset (ước lượng có giới hạn)"
            else:
                line += f" — {nd} dataset"
        st_fmt = _format_apiv2_source_state(st)
        if st_fmt:
            line += f" ({st_fmt})"
        lines.append(line)
    head = "Nguồn dữ liệu (REST `GET /apiv2/sources`):\n\n"
    if not lines:
        return head + "*(Chưa có source nào hoặc không có quyền xem.)*"
    footer = (
        "\n\n---\n"
        "*Gợi ý:* Endpoint này chỉ liệt kê **các kết nối (source) cấp cao nhất**. "
        "Thư mục, file và dataset trong UI (ví dụ trong **Samples**) không hiện thành "
        "từng dòng ở đây — chúng nằm **bên trong** source. "
        "Để liệt kê bảng/dataset cụ thể, hãy hỏi theo tên hoặc dùng luồng tìm kiếm catalog/SQL."
    )
    return head + "\n".join(lines) + footer


def _format_sources_answer(rows: list[dict[str, Any]], limit: int = 25) -> str:
    lines: list[str] = []
    for i, row in enumerate(rows[:limit], start=1):
        name = row.get("SCHEMA_NAME") or row.get("schema_name") or row.get("TABLE_SCHEMA")
        own = row.get("SCHEMA_OWNER") or row.get("schema_owner")
        if name is None:
            name = str(row)
        line = f"{i}. **{name}**"
        if own is not None and str(own).strip():
            line += f" (owner: {own})"
        lines.append(line)
    head = (
        "Top-level schemas / sources (from `INFORMATION_SCHEMA.SCHEMATA`). "
        "System schemas like `sys` are excluded.\n\n"
    )
    return head + "\n".join(lines) if lines else "No rows returned from metadata query."


def _format_discovered_tables_answer(
    tables: list[dict[str, Any]], limit: int = 80
) -> str:
    """Human-readable listing of catalog search hits (multiple tables/views)."""
    n = len(tables)
    head = (
        f"Tìm thấy **{n}** bảng hoặc view trong kết quả tìm kiếm catalog "
        f"(hiển thị tối đa **{min(limit, n)}**):\n\n"
    )
    lines: list[str] = []
    for i, t in enumerate(tables[:limit], start=1):
        typ = (t.get("type") or "?").strip()
        name = t.get("name") or "?"
        path = t.get("path")
        path_s = (
            " / ".join(str(p) for p in path)
            if isinstance(path, list)
            else (str(path) if path else "")
        )
        desc = (t.get("description") or "").strip()
        tags = t.get("tags")
        line = f"{i}. **{typ}** — `{name}`"
        if path_s:
            line += f"\n   - Đường dẫn: {path_s}"
        if isinstance(tags, list) and tags:
            line += f"\n   - Tags: {', '.join(str(x) for x in tags[:12])}"
        if desc:
            line += f"\n   - Mô tả: {_preview(desc, 280)}"
        lines.append(line)
    tail = ""
    if n > limit:
        tail = (
            f"\n\n*(Còn **{n - limit}** kết quả — thu hẹp câu hỏi trong ô chat hoặc "
            "lọc trong Dremio Catalog.)*"
        )
    footer = (
        "\n\n---\n"
        "*Gợi ý:* Để phân tích **một** bảng cụ thể (SQL), hãy gọi tên dataset hoặc "
        "đặt câu hỏi phân tích rõ ràng (không chỉ liệt kê)."
    )
    return head + "\n".join(lines) + tail + footer


def make_discovery_node(mcp_tools: list[Any]):
    """Interfaces with MCP to search tables/views matching the user question."""
    search_tool = get_search_tool(mcp_tools)
    sql_tool = get_sql_tool(mcp_tools)

    async def discovery_node(state: AgentState) -> dict[str, Any]:
        uq = state["user_question"] or ""
        _trace(
            "step=discovery MCP tool=SearchTableAndViews query=%s",
            _preview(uq, 200),
        )
        # Short path: list Dremio sources / top-level schemas (not dataset catalog search).
        if user_asks_for_dremio_sources_list(uq):
            _trace("step=discovery branch=list_sources (intent=sources_list)")
            auth_hdr = (state.get("dremio_auth_header") or "").strip()

            # 1) Gateway → Dremio REST with the same Authorization as `/aichat/*`
            #    (avoids MCP `@secured` falling back to a mismatched PAT in yaml).
            if auth_hdr:
                base_u = dremio_rest_base_url()
                rest_sources, http_st, rest_err = await _fetch_apiv2_sources(auth_hdr, base_u)
                if rest_sources is not None and http_st == 200:
                    ans = _format_apiv2_sources_answer(rest_sources)
                    return {
                        "discovery_short_circuit": True,
                        "assistant_answer": ans,
                        "discover_result": _json_compact({"sources": rest_sources[:60]}),
                        "discovered_tables": [],
                    }

            if not sql_tool:
                return {
                    "discovery_short_circuit": True,
                    "assistant_answer": (
                        "Không gọi được danh sách source qua REST (thiếu Authorization hoặc "
                        "lỗi HTTP). Không có RunSqlQuery trên MCP — kiểm tra "
                        "`tools.server_mode` và `local/mcp-oss.yaml`."
                    ),
                    "discover_result": "",
                    "discovered_tables": [],
                }
            meta_sql = """SELECT "SCHEMA_NAME", "SCHEMA_OWNER"
FROM INFORMATION_SCHEMA.SCHEMATA
WHERE "SCHEMA_NAME" NOT IN ('INFORMATION_SCHEMA', 'sys')
  AND "SCHEMA_NAME" NOT LIKE 'sys.%'
ORDER BY "SCHEMA_NAME"
LIMIT 40"""
            try:
                sql_out = await sql_tool.ainvoke({"query": meta_sql})
                rows, err = _rows_from_run_sql_output(sql_out)
                out_type = type(sql_out).__name__
                if err == "unparsed_tool_output":
                    return {
                        "discovery_short_circuit": True,
                        "assistant_answer": (
                            f"Phản hồi từ RunSqlQuery không parse được (kiểu: {out_type}). "
                            "Cần kiểm tra bản dremio-mcp / langchain-mcp-adapters."
                        ),
                        "discover_result": _json_compact(str(sql_out)[:2000]),
                        "discovered_tables": [],
                    }
                if err and not rows:
                    return {
                        "discovery_short_circuit": True,
                        "assistant_answer": f"Truy vấn metadata thất bại: {err}",
                        "discover_result": _json_compact(
                            sql_out if isinstance(sql_out, (dict, str, list)) else {"type": out_type}
                        ),
                        "discovered_tables": [],
                    }
                if rows:
                    ans = _format_sources_answer(rows)
                else:
                    ans = (
                        "Truy vấn `INFORMATION_SCHEMA.SCHEMATA` thành công nhưng "
                        "không còn schema nào sau khi lọc (hoặc cluster trống). "
                        "Hãy thêm nguồn dữ liệu trong Dremio UI."
                    )
                return {
                    "discovery_short_circuit": True,
                    "assistant_answer": ans,
                    "discover_result": _json_compact(
                        _mcp_tool_output_to_dict(sql_out) or str(sql_out)[:500]
                    ),
                    "discovered_tables": [],
                }
            except Exception as e:
                _trace("step=discovery list_sources_sql exception %s", e)
                return {
                    "discovery_short_circuit": True,
                    "assistant_answer": f"Lỗi khi gọi RunSqlQuery: {e!s}",
                    "discover_result": "",
                    "discovered_tables": [],
                }

        if not search_tool:
            _trace("step=discovery aborted SearchTableAndViews missing")
            return {
                "discover_result": "",
                "error": (
                    "MCP tool SearchTableAndViews not available. "
                    "On the Dremio MCP server set dremio.enable_search: true in config "
                    "(see tools-report.md / local/mcp-oss.yaml) and restart dremio-mcp-server."
                ),
            }
        try:
            nq = normalize_catalog_search_query(uq)
            out = await search_tool.ainvoke({"query": nq})
            _trace_verbose("step=discovery tool_output_type=%s", type(out).__name__)
            result_str = _json_compact(out)
            tables = _tables_from_search_tool_output(out)
            # #region agent log
            agent_debug_ndjson(
                "H2",
                "discovery.py:discovery_node",
                "post_search_normalize",
                {
                    "raw_question": (uq or "")[:500],
                    "normalized_query": (nq or "")[:500],
                    "tables_found": len(tables),
                    "multi_table_short_circuit_would": user_wants_multi_table_catalog_answer(uq),
                },
            )
            # #endregion
            if not tables:
                fallback_queries: list[str] = []
                for tok in _extract_dataset_token_candidates(uq):
                    if tok and tok != nq:
                        fallback_queries.append(tok)
                    stem = _stem_without_known_ext(tok)
                    if stem and stem not in fallback_queries and stem != nq:
                        fallback_queries.append(stem)
                for fq in fallback_queries:
                    try:
                        out_fb = await search_tool.ainvoke({"query": fq})
                        tables_fb = _tables_from_search_tool_output(out_fb)
                    except Exception as fe:
                        # #region agent log
                        agent_debug_ndjson(
                            "H8",
                            "discovery.py:discovery_node",
                            "fallback_search_error",
                            {"fallback_query": fq, "error": str(fe)[:240]},
                        )
                        # #endregion
                        continue
                    # #region agent log
                    agent_debug_ndjson(
                        "H8",
                        "discovery.py:discovery_node",
                        "fallback_search_attempt",
                        {"fallback_query": fq, "tables_found": len(tables_fb)},
                    )
                    # #endregion
                    if tables_fb:
                        out = out_fb
                        result_str = _json_compact(out_fb)
                        tables = tables_fb
                        _trace(
                            "step=discovery fallback_hit query=%s tables_found=%s",
                            _preview(fq, 80),
                            len(tables),
                        )
                        break
            _trace(
                "step=discovery done tables_found=%s excerpt=%s",
                len(tables),
                _preview(result_str, 280),
            )
            # Catalog-style question: return all matching tables without picking one for SQL/HITL.
            if user_wants_multi_table_catalog_answer(uq) and tables:
                ans = _format_discovered_tables_answer(tables)
                _trace(
                    "step=discovery branch=multi_table_catalog short_circuit count=%s",
                    len(tables),
                )
                return {
                    "discovery_short_circuit": True,
                    "assistant_answer": ans,
                    "discover_result": result_str,
                    "discovered_tables": tables,
                }
            if user_wants_multi_table_catalog_answer(uq) and not tables:
                _trace("step=discovery branch=multi_table_catalog empty_results")
                return {
                    "discovery_short_circuit": True,
                    "assistant_answer": (
                        "Không tìm thấy bảng/view nào khớp tìm kiếm catalog cho câu hỏi của bạn. "
                        "Thử từ khóa ngắn hơn (vd. `Samples`, `taxi`, `NYC`) hoặc kiểm tra quyền trên Dremio."
                    ),
                    "discover_result": result_str,
                    "discovered_tables": [],
                }
            return {"discover_result": result_str, "discovered_tables": tables}
        except Exception as e:
            _trace("step=discovery error %s", e)
            return {"discover_result": "", "error": f"Discovery failed: {e!s}"}

    return discovery_node


def _format_no_results_answer(user_question: str) -> str:
    """Friendly Vietnamese answer when catalog search + fallback both yield 0 datasets."""
    tokens = _extract_dataset_token_candidates(user_question)
    head_token = tokens[0] if tokens else None
    is_csv = bool(head_token and head_token.lower().endswith(".csv"))

    head = "**Không tìm thấy bảng/dataset nào** khớp với câu hỏi của bạn trong Dremio catalog.\n\n"
    if head_token:
        head += f"Bạn nhắc đến: `{head_token}`. Một số nguyên nhân thường gặp:\n"
    else:
        head += "Một số nguyên nhân thường gặp:\n"

    common = (
        "1. Dataset **chưa được promote**: vào Dremio UI, mở file (CSV/JSON/Parquet…), "
        "bấm **Format** rồi chọn loại file để Dremio nhận diện thành bảng truy vấn được.\n"
        "2. Dataset nằm ở **source khác** (không phải `Samples`). Hãy chỉ rõ đường dẫn đầy đủ, "
        "ví dụ: `\"S3-data\".\"folder\".\"NYC-taxi-trips.csv\"`.\n"
        "3. Tên dataset khác chính tả hoặc đuôi mở rộng — kiểm tra trong Dremio UI và sao chép tên FQN.\n"
        "4. PAT/credential không đủ quyền xem dataset đó (chỉ admin mới thấy được).\n"
    )
    if is_csv:
        common += (
            "5. **Đặc thù CSV**: Dremio yêu cầu file CSV được promote thủ công với tham số "
            "(delimiter, header) trước khi xuất hiện trong search.\n"
        )
    footer = (
        "\n**Bạn có thể thử:**\n"
        "- *\"liệt kê các bảng trong Samples\"* để xem những dataset đã promote.\n"
        "- *\"các nguồn dữ liệu trong Dremio\"* để xem source nào đang kết nối.\n"
        "- Sao chép FQN trực tiếp từ Dremio UI và gửi lại."
    )
    return head + common + footer


def _table_fqn_from_single_discovery_row(row: dict[str, Any]) -> str | None:
    """Catalog search row uses ``name`` as the Dremio FQN string."""
    n = row.get("name")
    if isinstance(n, str):
        s = n.strip()
        if s:
            return s
    return None


def make_pick_and_schema_node(llm: Any, mcp_tools: list[Any]):
    """Picks the best table from discovery results and fetches its schema."""
    schema_tool = get_schema_tool(mcp_tools)
    structured_pick = llm.with_structured_output(TablePick)

    async def pick_and_schema_node(state: AgentState) -> dict[str, Any]:
        if state.get("error"):
            return {}

        _trace("step=pick_and_schema resolve table from discovery")
        discovered = state.get("discovered_tables") or []

        if not discovered:
            uq = state.get("user_question") or ""
            ans = _format_no_results_answer(uq)
            # #region agent log
            agent_debug_ndjson(
                "H9",
                "discovery.py:pick_and_schema_node",
                "empty_discovery_short_circuit",
                {"question_preview": uq[:200], "answer_chars": len(ans)},
            )
            # #endregion
            _trace(
                "step=pick_and_schema empty_discovery short_circuit answer_chars=%s",
                len(ans),
            )
            return {
                "schema_short_circuit": True,
                "assistant_answer": ans,
            }

        table_fqn: str | None = None
        if len(discovered) == 1 and isinstance(discovered[0], dict):
            table_fqn = _table_fqn_from_single_discovery_row(discovered[0])
            if table_fqn:
                _trace(
                    "step=pick_and_schema skip_llm single_catalog_hit table=%s",
                    _preview(table_fqn, 220),
                )
                # #region agent log
                agent_debug_ndjson(
                    "H1",
                    "discovery.py:pick_and_schema_node",
                    "single_table_skip_llm",
                    {"table_fqn_preview": table_fqn[:240]},
                )
                # #endregion

        if not table_fqn:
            ctx = (state.get("user_context") or "").strip()
            sys = (
                "/no_think\n"
                "You pick exactly one Dremio table or view FQN from the discovery JSON below. "
                "Use the exact name string as it appears in the data."
            )
            if ctx:
                sys += f"\n\nUser context:\n{ctx}"
            msg = f"User question:\n{state['user_question']}\n\nDiscovery:\n{state.get('discover_result', '')}"
            _trace_verbose(
                "step=pick_and_schema prompt_chars sys=%s user=%s discovery=%s",
                len(sys),
                len(state.get("user_question") or ""),
                len(state.get("discover_result") or ""),
            )
            if _agent_trace_llm_content_on():
                _trace_verbose("step=pick_and_schema prompt_sys=%s", _preview(sys, 1000))
                _trace_verbose("step=pick_and_schema prompt_user=%s", _preview(msg, 1200))
            try:
                # #region agent log
                _t_pick0 = time.perf_counter()
                agent_debug_ndjson(
                    "H1",
                    "discovery.py:pick_and_schema_node",
                    "before_structured_pick",
                    {"question_len": len(state.get("user_question") or "")},
                )
                # #endregion
                _msgs = [SystemMessage(content=sys), HumanMessage(content=msg)]
                _to = structured_llm_timeout_seconds()
                if _to is not None:
                    pick = await asyncio.wait_for(
                        structured_pick.ainvoke(_msgs),
                        timeout=_to,
                    )
                else:
                    pick = await structured_pick.ainvoke(_msgs)
                table_fqn = pick.table_fqn.strip()
                # #region agent log
                agent_debug_ndjson(
                    "H1",
                    "discovery.py:pick_and_schema_node",
                    "after_structured_pick",
                    {
                        "elapsed_ms": int((time.perf_counter() - _t_pick0) * 1000),
                        "table_fqn_preview": (table_fqn or "")[:240],
                    },
                )
                # #endregion
                _trace_verbose(
                    "step=pick_and_schema parsed_pick=%s",
                    _preview(_json_compact(pick.model_dump()), 900),
                )
                _trace(
                    "step=pick_and_schema picked table=%s rationale=%s",
                    table_fqn,
                    _preview(pick.rationale, 160),
                )
            except Exception as e:
                # #region agent log
                agent_debug_ndjson(
                    "H1",
                    "discovery.py:pick_and_schema_node",
                    "structured_pick_exception",
                    {"error_type": type(e).__name__, "error": str(e)[:500]},
                )
                # #endregion
                _trace("step=pick_and_schema pick_failed %s", e)
                return {"error": f"Table pick failed: {e!s}"}

        if not schema_tool:
            _trace("step=pick_and_schema schema_tool missing table=%s", table_fqn)
            return {"table_fqn": table_fqn, "schema_text": "(schema tool unavailable)"}
        try:
            _trace(
                "step=pick_and_schema MCP tool=GetSchemaOfTable table=%s",
                table_fqn,
            )
            out = await schema_tool.ainvoke({"table_name": table_fqn})
            # #region agent log
            _raw_preview = (out if isinstance(out, str) else repr(out))[:240]
            agent_debug_ndjson(
                "H6",
                "discovery.py:pick_and_schema_node",
                "schema_tool_raw_output",
                {"out_type": type(out).__name__, "out_prefix": _raw_preview},
            )
            # #endregion
            _sch = _json_compact(out, max_len=16000)
            # #region agent log
            agent_debug_ndjson(
                "H5",
                "discovery.py:pick_and_schema_node",
                "schema_fetch_ok",
                {
                    "schema_chars": len(_sch),
                    "schema_starts_with": _sch[:80],
                    "table_fqn_preview": (table_fqn or "")[:240],
                },
            )
            # #endregion
            _trace(
                "step=pick_and_schema schema_ok chars=%s",
                len(_sch),
            )

            uq = state.get("user_question") or ""
            if user_asks_for_schema_description(uq):
                ans = _format_schema_description_answer(_sch, table_fqn)
                # #region agent log
                agent_debug_ndjson(
                    "H7",
                    "discovery.py:pick_and_schema_node",
                    "schema_short_circuit_taken",
                    {
                        "question_preview": uq[:200],
                        "answer_chars": len(ans),
                        "table_fqn_preview": (table_fqn or "")[:240],
                    },
                )
                # #endregion
                _trace(
                    "step=pick_and_schema schema_short_circuit table=%s answer_chars=%s",
                    table_fqn,
                    len(ans),
                )
                return {
                    "table_fqn": table_fqn,
                    "schema_text": _sch,
                    "schema_short_circuit": True,
                    "assistant_answer": ans,
                }
            return {"table_fqn": table_fqn, "schema_text": _sch}
        except Exception as e:
            # #region agent log
            agent_debug_ndjson(
                "H5",
                "discovery.py:pick_and_schema_node",
                "schema_fetch_error",
                {"error_type": type(e).__name__, "error": str(e)[:500]},
            )
            # #endregion
            _trace("step=pick_and_schema schema_error %s", e)
            return {"table_fqn": table_fqn, "schema_text": "", "error": f"Schema fetch failed: {e!s}"}

    return pick_and_schema_node


def route_after_discovery(
    state: AgentState,
) -> Literal["pick_and_schema", "error_end", "early_end"]:
    if state.get("error"):
        nxt: Literal["pick_and_schema", "error_end", "early_end"] = "error_end"
    elif state.get("discovery_short_circuit"):
        nxt = "early_end"
    else:
        nxt = "pick_and_schema"
    _trace("route discovery -> %s", nxt)
    return nxt


def route_after_pick_schema(
    state: AgentState,
) -> Literal["metadata_confirmation", "early_end", "error_end"]:
    if state.get("error"):
        nxt: Literal["metadata_confirmation", "early_end", "error_end"] = "error_end"
    elif state.get("schema_short_circuit"):
        nxt = "early_end"
    else:
        nxt = "metadata_confirmation"
    _trace("route pick_and_schema -> %s", nxt)
    return nxt
