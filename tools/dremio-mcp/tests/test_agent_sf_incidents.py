"""End-to-end test suite for the Dremio SQL HITL Agent against ``SF_incidents2016``.

Test này gọi thẳng FastAPI gateway (``dremio-sql-agent``) qua HTTP nên cần các
service sau đang chạy thật trên localhost:

    # Terminal 1 — MCP server (mặc định http://127.0.0.1:8080/mcp/)
    uv run dremio-mcp-server run -c local/mcp-oss.yaml --enable-streaming-http --port 8080

    # Terminal 2 — Gateway (mặc định http://127.0.0.1:9292)
    # Default LLM = qwen2.5:3b (xem gateway/config.py): có thinking-mode tắt
    # nên `with_structured_output` cho sql_gen chạy ~11s trên CPU. Đừng dùng
    # qwen3.5:* mặc định vì model đó luôn "thinking" và langchain-ollama 0.3
    # không có cách truyền `think: false` ⇒ sql_gen sẽ time-out.
    uv run dremio-sql-agent

    # (tuỳ chọn) PAT — nếu không set, file sẽ đọc dremio.pat từ local/mcp-oss.yaml
    export DREMIO_PAT=<paste PAT>

Chạy bằng pytest::

    uv run pytest tools/dremio-mcp/tests/test_agent_sf_incidents.py -v -s

Chạy như script (in PASS/FAIL có màu, không bị ``-x`` cắt giữa chừng)::

    uv run python tools/dremio-mcp/tests/test_agent_sf_incidents.py

Mỗi test mô phỏng một quy trình hội thoại đầy đủ qua các bước HITL (metadata
confirmation + SQL refinement) hoặc một short-circuit (greeting, guardrail
reject, schema-only, không tìm thấy bảng, liệt kê nguồn dữ liệu).

Lưu ý về hiệu năng: qwen3.5:4b chạy trên CPU thường rất chậm (~1-2 phút mỗi
lần gọi LLM). Các test ``test_full_flow_count_by_category``,
``test_out_of_scope_rejected``, ``test_dml_rejected_by_guardrail``,
``test_edit_sql_at_refinement`` đều cần LLM nên có thể mất nhiều phút mỗi test.
Các test còn lại đi đường fast-path (regex / data_safe / REST) nên rất nhanh.
Bạn có thể chỉnh timeout bằng env ``DREMIO_AGENT_TEST_TIMEOUT`` (giây).
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import sys
import traceback
import uuid
from pathlib import Path
from typing import Any

import httpx
import pytest

# ---------------------------------------------------------------------------
# Config + constants
# ---------------------------------------------------------------------------

GATEWAY_URL = os.environ.get("DREMIO_AGENT_GATEWAY", "http://127.0.0.1:9292")
MCP_URL = os.environ.get("DREMIO_MCP_URL", "http://127.0.0.1:8080/mcp/")
DEFAULT_TIMEOUT = float(os.environ.get("DREMIO_AGENT_TEST_TIMEOUT", "900"))

TABLE_FRAGMENT = "SF_incidents2016"
EXPECTED_COLUMNS: list[str] = [
    "IncidntNum",
    "Category",
    "Descript",
    "DayOfWeek",
    "Date",
    "Time",
    "PdDistrict",
    "Resolution",
    "Address",
    "X",
    "Y",
    "Location",
    "PdId",
]


# ---------------------------------------------------------------------------
# Tiny ANSI helper for script mode (NO-OP under pytest because _SCRIPT_MODE is False)
# ---------------------------------------------------------------------------


class _Ansi:
    R = "\033[31m"
    G = "\033[32m"
    Y = "\033[33m"
    B = "\033[34m"
    C = "\033[36m"
    DIM = "\033[2m"
    BOLD = "\033[1m"
    RST = "\033[0m"


_SCRIPT_MODE = False


def _short(s: Any, n: int = 200) -> str:
    text = str(s).replace("\n", " ").strip()
    return text if len(text) <= n else text[: n - 3] + "..."


# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------


_PreflightResult = tuple[bool, str | None, str | None]
_PREFLIGHT_CACHE: _PreflightResult | None = None


def _load_pat_from_yaml() -> str | None:
    """Fallback PAT lookup — reads ``dremio.pat`` from ``local/mcp-oss.yaml``."""
    try:
        import yaml  # PyYAML is in dremioai's dependency tree.
    except ImportError:
        return None
    p = Path(__file__).resolve().parents[1] / "local" / "mcp-oss.yaml"
    if not p.is_file():
        return None
    try:
        data = yaml.safe_load(p.read_text(encoding="utf-8"))
    except Exception:
        return None
    if isinstance(data, dict):
        dremio_cfg = data.get("dremio")
        if isinstance(dremio_cfg, dict):
            pat = dremio_cfg.get("pat")
            if isinstance(pat, str) and pat.strip():
                return pat.strip()
    return None


def _preflight() -> _PreflightResult:
    """Check gateway + MCP availability and resolve PAT (cached for whole run)."""
    global _PREFLIGHT_CACHE
    if _PREFLIGHT_CACHE is not None:
        return _PREFLIGHT_CACHE

    try:
        with httpx.Client(timeout=10) as c:
            r = c.get(f"{GATEWAY_URL}/aichat/health")
        if r.status_code != 200:
            _PREFLIGHT_CACHE = (
                False,
                None,
                f"gateway /aichat/health returned {r.status_code} (start `uv run dremio-sql-agent`)",
            )
            return _PREFLIGHT_CACHE
    except Exception as e:
        _PREFLIGHT_CACHE = (
            False,
            None,
            f"gateway unreachable at {GATEWAY_URL}: {e!s}",
        )
        return _PREFLIGHT_CACHE

    pat = os.environ.get("DREMIO_PAT", "").strip() or _load_pat_from_yaml()
    if not pat:
        _PREFLIGHT_CACHE = (
            False,
            None,
            "Missing PAT — set DREMIO_PAT env var, or fill dremio.pat in local/mcp-oss.yaml",
        )
        return _PREFLIGHT_CACHE

    # Note: ``/aichat/v1/config`` only reports ``mcp_configured=true`` when the
    # ``DREMIO_MCP_URL`` env var is set on the gateway process. The actual MCP
    # default URL is hard-coded, so we don't gate on that flag here — failing
    # MCP calls will surface as concrete errors in the relevant test instead.
    try:
        with httpx.Client(timeout=10) as c:
            r = c.get(f"{GATEWAY_URL}/aichat/v1/config")
        if r.status_code != 200:
            _PREFLIGHT_CACHE = (
                False,
                None,
                f"gateway /aichat/v1/config returned {r.status_code}",
            )
            return _PREFLIGHT_CACHE
    except Exception as e:
        _PREFLIGHT_CACHE = (
            False,
            None,
            f"gateway /aichat/v1/config failed: {e!s}",
        )
        return _PREFLIGHT_CACHE

    _PREFLIGHT_CACHE = (True, pat, None)
    return _PREFLIGHT_CACHE


def _require_gateway() -> str:
    ok, pat, reason = _preflight()
    if not ok or not pat:
        pytest.skip(reason or "preflight failed")
    return pat


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------


class AgentClient:
    """Tiny async wrapper around the two HITL endpoints of the gateway."""

    def __init__(
        self,
        pat: str,
        base: str = GATEWAY_URL,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self._client = httpx.AsyncClient(
            base_url=base,
            timeout=timeout,
            headers={"Authorization": f"Bearer {pat}"},
        )

    async def __aenter__(self) -> "AgentClient":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self._client.aclose()

    async def chat(self, message: str, thread_id: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"message": message}
        if thread_id:
            body["thread_id"] = thread_id
        r = await self._client.post("/aichat/v1/chat", json=body)
        _raise_with_body(r)
        return r.json()

    async def resume(
        self,
        thread_id: str,
        action: str,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"thread_id": thread_id, "action": action}
        if payload is not None:
            body["payload"] = payload
        r = await self._client.post("/aichat/v1/chat/resume", json=body)
        _raise_with_body(r)
        return r.json()


def _raise_with_body(r: httpx.Response) -> None:
    if r.is_success:
        return
    body = r.text[:600].replace("\n", " ")
    raise AssertionError(
        f"HTTP {r.status_code} from {r.request.method} {r.request.url}: {body}"
    )


# ---------------------------------------------------------------------------
# Assertion helpers
# ---------------------------------------------------------------------------


def assert_status(resp: dict[str, Any], expected: str, *, node: str | None = None) -> None:
    actual = resp.get("status")
    if actual != expected:
        raise AssertionError(
            f"Expected status={expected!r}"
            f"{f' node={node!r}' if node else ''}, "
            f"got status={actual!r} node={resp.get('node')!r} "
            f"error={resp.get('error')!r} "
            f"answer_excerpt={_short(resp.get('answer') or '', 200)!r}"
        )
    if node is not None and resp.get("node") != node:
        raise AssertionError(
            f"Expected interrupt node={node!r}, got node={resp.get('node')!r} "
            f"interrupt={_short(json.dumps(resp.get('interrupt') or {}, default=str), 200)}"
        )


def extract_interrupt(resp: dict[str, Any]) -> dict[str, Any]:
    intr = resp.get("interrupt")
    if isinstance(intr, list):
        if not intr:
            raise AssertionError("interrupt list is empty")
        return intr[0]
    if isinstance(intr, dict):
        return intr
    raise AssertionError(f"interrupt missing or not dict: {intr!r}")


def assert_has_columns(text: str, columns: list[str]) -> None:
    missing = [c for c in columns if c not in text]
    if missing:
        raise AssertionError(
            f"Missing {len(missing)} column(s) in payload: {missing!r}. "
            f"Payload excerpt: {_short(text, 400)!r}"
        )


def _parse_execution_result(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {"raw": parsed}
        except json.JSONDecodeError:
            return {"raw_str": raw}
    return {"raw_value": raw}


def _pretty(label: str, resp: dict[str, Any]) -> None:
    if not _SCRIPT_MODE:
        return
    print(
        f"  {_Ansi.DIM}{label}{_Ansi.RST} "
        f"status={resp.get('status')} node={resp.get('node')!r} "
        f"thread_id={_short(resp.get('thread_id', ''), 40)}"
    )
    intr_raw = resp.get("interrupt")
    if intr_raw:
        intr = extract_interrupt(resp)
        if intr.get("action"):
            print(f"    {_Ansi.DIM}interrupt.action={intr.get('action')}{_Ansi.RST}")
        if intr.get("table_fqn"):
            print(
                f"    {_Ansi.DIM}interrupt.table_fqn={_short(str(intr.get('table_fqn')), 140)}{_Ansi.RST}"
            )
        if intr.get("proposed_sql"):
            print(
                f"    {_Ansi.DIM}interrupt.proposed_sql={_short(str(intr.get('proposed_sql')), 240)}{_Ansi.RST}"
            )
    if resp.get("answer"):
        print(f"    {_Ansi.DIM}answer={_short(resp.get('answer'), 240)}{_Ansi.RST}")
    if resp.get("error"):
        print(f"    {_Ansi.DIM}error={_short(resp.get('error'), 240)}{_Ansi.RST}")


def _thread_id(label: str) -> str:
    return f"sf-test-{label}-{uuid.uuid4().hex[:8]}"


# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_full_flow_count_by_category() -> None:
    """Happy path: chat -> approve metadata -> approve SQL -> completed answer."""
    pat = _require_gateway()
    tid = _thread_id("happy")
    async with AgentClient(pat) as agent:
        r1 = await agent.chat(
            "Đếm số sự cố theo Category trong bảng SF_incidents2016, lấy top 5",
            thread_id=tid,
        )
        _pretty("[1/3] chat", r1)
        assert_status(r1, "interrupted", node="metadata_confirmation")

        intr = extract_interrupt(r1)
        table_fqn = str(intr.get("table_fqn") or "")
        assert TABLE_FRAGMENT in table_fqn, (
            f"table_fqn should contain {TABLE_FRAGMENT!r}, got {table_fqn!r}"
        )
        schema_text = str(intr.get("schema_text") or "")
        assert_has_columns(schema_text, EXPECTED_COLUMNS)

        r2 = await agent.resume(tid, "approve")
        _pretty("[2/3] approve metadata", r2)
        assert_status(r2, "interrupted", node="refinement")

        intr2 = extract_interrupt(r2)
        sql = str(intr2.get("proposed_sql") or "")
        assert re.match(r"^\s*select\b", sql, re.IGNORECASE), (
            f"proposed_sql is not a SELECT: {sql!r}"
        )
        assert re.search(r"\bcategory\b", sql, re.IGNORECASE), (
            f"proposed_sql missing Category column: {sql!r}"
        )
        assert re.search(r"\bgroup\s+by\b", sql, re.IGNORECASE), (
            f"proposed_sql missing GROUP BY: {sql!r}"
        )

        r3 = await agent.resume(tid, "approve")
        _pretty("[3/3] approve sql", r3)
        assert_status(r3, "completed")
        ans = (r3.get("answer") or "").strip()
        assert ans, f"answer was empty after execute: resp={_short(json.dumps(r3, default=str), 400)}"
        ex = _parse_execution_result(r3.get("execution_result"))
        assert "error" not in ex, f"execution_result contained error: {ex!r}"


@pytest.mark.asyncio
async def test_greeting_short_circuit() -> None:
    """Greeting goes straight to `greetings_node` — no discovery, no HITL."""
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat("xin chao", thread_id=_thread_id("greet"))
        _pretty("[1/1] greeting", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert "Tôi là mô hình" in ans, (
            f"greeting answer should be the canned reply, got {ans!r}"
        )


@pytest.mark.asyncio
async def test_out_of_scope_rejected() -> None:
    """A clearly non-data request must be rejected by the guardrail."""
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat(
            "Hãy kể cho tôi một câu chuyện cười về mèo",
            thread_id=_thread_id("oos"),
        )
        _pretty("[1/1] out-of-scope", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert ans.startswith("Yeu cau nam ngoai pham vi"), (
            f"answer should start with guardrail rejection prefix, got {ans!r}"
        )


@pytest.mark.asyncio
async def test_dml_rejected_by_guardrail() -> None:
    """DDL/DML attempts must be classified UNSAFE — agent never reaches discovery.

    Note: ``_DATA_SAFE_RE`` in ``guardrail.py`` contains ``\\btable\\b``, so the
    phrase ``DROP TABLE ...`` would hit the fast-path SAFE branch and bypass the
    LLM classifier entirely. We deliberately pick ``TRUNCATE`` to avoid that
    word-boundary match and exercise the actual UNSAFE detection path.
    """
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat(
            "TRUNCATE SF_incidents2016",
            thread_id=_thread_id("dml"),
        )
        _pretty("[1/1] dml", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert ans.startswith("Yeu cau nam ngoai pham vi"), (
            f"DML answer should be guardrail rejection, got {ans!r}"
        )


@pytest.mark.asyncio
async def test_schema_describe_no_hitl() -> None:
    """A schema-only ask triggers `schema_short_circuit` — no HITL, ships full schema.

    Note: phrasing must hit ``_SCHEMA_DESCRIBE_RX`` (e.g. ``cấu trúc bảng``)
    *without* also matching ``_MULTI_TABLE_CATALOG_RX`` (which would
    short-circuit earlier in discovery). ``Mô tả các cột ...`` for instance
    matches ``mô\\s*tả\\s+các`` in the multi-table regex and short-circuits at
    discovery instead, so we phrase the question with ``cấu trúc bảng``.
    """
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat(
            "Mô tả cấu trúc bảng SF_incidents2016",
            thread_id=_thread_id("schema"),
        )
        _pretty("[1/1] schema describe", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert "Số trường (cột):** 13" in ans, (
            f"missing 13-column marker. answer head={_short(ans, 400)!r}"
        )
        assert_has_columns(ans, EXPECTED_COLUMNS)


@pytest.mark.asyncio
async def test_wrong_table_name_no_results() -> None:
    """Bogus dataset name must short-circuit at `pick_and_schema` with friendly message."""
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat(
            "Đếm số dòng trong bảng SF_incidents9999.csv",
            thread_id=_thread_id("wrong"),
        )
        _pretty("[1/1] wrong table", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert "Không tìm thấy bảng/dataset" in ans, (
            f"answer should be the 'not found' message, got {_short(ans, 400)!r}"
        )


@pytest.mark.asyncio
async def test_reject_metadata_cancels() -> None:
    """If user rejects metadata, graph terminates at `reject_end` without SQL."""
    pat = _require_gateway()
    tid = _thread_id("reject-meta")
    async with AgentClient(pat) as agent:
        r1 = await agent.chat(
            "Đếm số sự cố theo Category trong bảng SF_incidents2016, lấy top 5",
            thread_id=tid,
        )
        _pretty("[1/2] chat", r1)
        assert_status(r1, "interrupted", node="metadata_confirmation")

        r2 = await agent.resume(tid, "reject")
        _pretty("[2/2] reject metadata", r2)
        assert_status(r2, "completed")
        ans = r2.get("answer") or ""
        assert "Da huy" in ans, (
            f"rejection answer should mention 'Da huy', got {_short(ans, 400)!r}"
        )


@pytest.mark.asyncio
async def test_edit_sql_at_refinement() -> None:
    """User edits SQL at refinement HITL — agent executes the user-supplied query."""
    pat = _require_gateway()
    tid = _thread_id("edit-sql")
    async with AgentClient(pat) as agent:
        r1 = await agent.chat(
            "Lấy 5 dòng đầu của bảng SF_incidents2016",
            thread_id=tid,
        )
        _pretty("[1/3] chat", r1)
        assert_status(r1, "interrupted", node="metadata_confirmation")

        intr = extract_interrupt(r1)
        table_fqn = str(intr.get("table_fqn") or "").strip()
        assert TABLE_FRAGMENT in table_fqn, (
            f"table_fqn missing {TABLE_FRAGMENT!r}: {table_fqn!r}"
        )

        r2 = await agent.resume(tid, "approve")
        _pretty("[2/3] approve metadata", r2)
        assert_status(r2, "interrupted", node="refinement")

        custom_sql = f'SELECT "Category", "PdDistrict" FROM {table_fqn} LIMIT 3'
        r3 = await agent.resume(tid, "edit", payload={"sql": custom_sql})
        _pretty("[3/3] edit + execute", r3)
        assert_status(r3, "completed")

        ex = _parse_execution_result(r3.get("execution_result"))
        assert "error" not in ex, f"execution_result had error: {ex!r}"
        rows = ex.get("result")
        if not isinstance(rows, list):
            raise AssertionError(
                f"execution_result.result is not a list — got {type(rows).__name__}: {ex!r}"
            )
        assert len(rows) == 3, f"expected 3 rows from LIMIT 3, got {len(rows)}: {rows!r}"
        for row in rows:
            assert isinstance(row, dict), f"row not dict: {row!r}"
            assert set(row.keys()) == {"Category", "PdDistrict"}, (
                f"unexpected columns in row: {set(row.keys())!r} — expected just Category + PdDistrict"
            )


@pytest.mark.asyncio
async def test_list_sources_short_circuit() -> None:
    """Catalog-level question about sources hits the REST `/apiv2/sources` short-circuit."""
    pat = _require_gateway()
    async with AgentClient(pat) as agent:
        r = await agent.chat(
            "liệt kê các nguồn dữ liệu trong dremio",
            thread_id=_thread_id("sources"),
        )
        _pretty("[1/1] list sources", r)
        assert_status(r, "completed")
        ans = r.get("answer") or ""
        assert "Nguồn dữ liệu" in ans, (
            f"sources answer should include 'Nguồn dữ liệu' header, got {_short(ans, 400)!r}"
        )


# ---------------------------------------------------------------------------
# Script-mode runner — kept here so `python test_agent_sf_incidents.py` works
# ---------------------------------------------------------------------------


_ALL_CASES = [
    test_full_flow_count_by_category,
    test_greeting_short_circuit,
    test_out_of_scope_rejected,
    test_dml_rejected_by_guardrail,
    test_schema_describe_no_hitl,
    test_wrong_table_name_no_results,
    test_reject_metadata_cancels,
    test_edit_sql_at_refinement,
    test_list_sources_short_circuit,
]


try:
    from _pytest.outcomes import Skipped as _PytestSkipped
except ImportError:  # pragma: no cover
    class _PytestSkipped(Exception):  # type: ignore[no-redef]
        pass


async def _run_as_script(cases: list[Any]) -> int:
    """Run all test functions sequentially with coloured PASS/FAIL output."""
    global _SCRIPT_MODE
    _SCRIPT_MODE = True

    print(f"{_Ansi.BOLD}=== Dremio SQL Agent — SF_incidents2016 E2E ==={_Ansi.RST}")
    print(f"  gateway   = {GATEWAY_URL}")
    print(f"  mcp_url   = {MCP_URL}")

    ok, pat, reason = _preflight()
    if not ok:
        print(f"{_Ansi.Y}[SKIP ALL]{_Ansi.RST} preflight failed: {reason}")
        return 0
    pat_preview = (pat or "")[:4] + "…" if pat else ""
    print(f"  pat       = {pat_preview} (loaded)\n")

    fails: list[tuple[str, str]] = []
    skipped: list[tuple[str, str]] = []
    n = len(cases)
    for i, fn in enumerate(cases, 1):
        name = fn.__name__
        print(f"{_Ansi.B}[{i}/{n}] {name}{_Ansi.RST}")
        try:
            await fn()
            print(f"  {_Ansi.G}[PASS]{_Ansi.RST} {name}\n")
        except _PytestSkipped as se:
            skipped.append((name, str(se)))
            print(f"  {_Ansi.Y}[SKIP]{_Ansi.RST} {name}: {se}\n")
        except AssertionError as ae:
            tb = traceback.format_exc(limit=4)
            fails.append((name, str(ae)))
            print(f"  {_Ansi.R}[FAIL]{_Ansi.RST} {name}: {ae}")
            print(f"{_Ansi.DIM}{tb}{_Ansi.RST}")
        except Exception as e:
            tb = traceback.format_exc(limit=6)
            fails.append((name, f"{type(e).__name__}: {e}"))
            print(f"  {_Ansi.R}[ERROR]{_Ansi.RST} {name}: {type(e).__name__}: {e}")
            print(f"{_Ansi.DIM}{tb}{_Ansi.RST}")

    print()
    passed = n - len(fails) - len(skipped)
    if fails:
        print(
            f"{_Ansi.R}{_Ansi.BOLD}=== {len(fails)}/{n} FAILED "
            f"({passed} passed, {len(skipped)} skipped) ==={_Ansi.RST}"
        )
        for name, msg in fails:
            print(f"  - {_Ansi.R}{name}{_Ansi.RST}: {_short(msg, 240)}")
    else:
        print(
            f"{_Ansi.G}{_Ansi.BOLD}=== {passed}/{n} PASSED "
            f"({len(skipped)} skipped) ==={_Ansi.RST}"
        )
    return len(fails)


if __name__ == "__main__":
    rc = asyncio.run(_run_as_script(_ALL_CASES))
    sys.exit(rc)
