#
#  Copyright (C) 2017-2025 Dremio Corporation
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

from pydantic import (
    BaseModel,
    Field,
    ConfigDict,
    field_validator,
)
from typing import (
    Any,
    Dict,
    List,
    Optional,
    Union,
)
from dremioai.api.util import UStrEnum
from datetime import datetime
from enum import auto
from dremioai.config import settings
from dremioai.api.transport import DremioAsyncHttpClient as AsyncHttpClient
from dremioai.api.dremio.catalog import get_schemas
import pandas as pd


class QueryType(UStrEnum):
    UI_RUN = auto()
    UI_PREVIEW = auto()
    UI_INTERNAL_PREVIEW = auto()
    UI_INTERNAL_RUN = auto()
    UI_EXPORT = auto()
    ODBC = auto()
    JDBC = auto()
    REST = auto()
    ACCELERATOR_CREATE = auto()
    ACCELERATOR_DROP = auto()
    UNKNOWN = auto()
    PREPARE_INTERNAL = auto()
    ACCELERATOR_EXPLAIN = auto()
    UI_INITIAL_PREVIEW = auto()
    FLIGHT = auto()
    METADATA_REFRESH = auto()
    INTERNAL_ICEBERG_METADATA_DROP = auto()
    D2D = auto()
    ACCELERATOR_OPTIMIZE = auto()
    COPY_ERRORS_PLAN = auto()


class JobStatus(UStrEnum):
    NOT_SUBMITTED = auto()
    STARTING = auto()
    RUNNING = auto()
    COMPLETED = auto()
    CANCELED = auto()
    FAILED = auto()
    CANCELLATION_REQUESTED = auto()
    ENQUEUED = auto()
    PLANNING = auto()
    PENDING = auto()
    METADATA_RETRIEVAL = auto()
    QUEUED = auto()
    ENGINE_START = auto()
    EXECUTION_PLANNING = auto()
    INVALID_STATE = auto()


class Category(UStrEnum):
    JOB = auto()
    VIEW = auto()
    TABLE = auto()
    FOLDER = auto()
    UDF = auto()
    SPACE = auto()
    REFLECTION = auto()
    SCRIPT = auto()
    SOURCE = auto()


class UserOrRole(UStrEnum):
    UNSPECIFIED = auto()
    USER = auto()
    ROLE = auto()


class EnterpriseDatasetType(UStrEnum):
    TABLE = auto()
    VIEW = auto()


class EnterpriseSearchUserOrRoleObject(BaseModel):
    id: Optional[str] = None
    type: Optional[UserOrRole] = None
    username: Optional[str] = None
    rolename: Optional[str] = None


class EnterpriseDatasetObject(BaseModel):
    type: Optional[str] = Field(None, alias="datasetType")
    path: Optional[List[str]] = Field(None, alias="datasetPath")


class EnterpriseSearchJobObject(BaseModel):
    id: Optional[str] = None
    queried_ds: Optional[List[EnterpriseDatasetObject]] = Field(
        None, alias="queriedDatasets"
    )
    sql: Optional[str] = None
    job_type: Optional[QueryType] = Field(None, alias="jobType")
    user: Optional[EnterpriseSearchUserOrRoleObject] = None
    start_time: Optional[datetime] = Field(None, alias="startTime")
    finish_time: Optional[datetime] = Field(None, alias="finishTime")
    job_status: Optional[JobStatus] = Field(None, alias="jobStatus")
    error: Optional[str] = None


class EnterpriseSearchScriptObject(BaseModel):
    id: Optional[str] = None
    name: Optional[str] = None
    owner: Optional[EnterpriseSearchUserOrRoleObject] = None
    description: Optional[str] = None
    content: Optional[str] = None
    created_at: Optional[datetime] = Field(default=None, alias="createdAt")
    modified_at: Optional[datetime] = Field(default=None, alias="lastModifiedAt")


class EnterpriseSearchReflectionObject(BaseModel):
    id: Optional[str] = None
    name: Optional[str] = None
    type: Optional[str] = Field(default=None, alias="datasetType")
    path: Optional[List[str]] = Field(default=None, alias="datasetPath")
    branch: Optional[str] = Field(default=None, alias="datasetBranch")
    created_at: Optional[datetime] = Field(default=None, alias="createdAt")
    modified_at: Optional[datetime] = Field(default=None, alias="lastModifiedAt")


class EnterpriseSearchCatalogObject(BaseModel):
    path: Optional[List[str]] = None
    sub_paths: Optional[List[str]] = Field(default=None, alias="subPaths")
    type: Optional[str] = None
    branch: Optional[str] = None
    labels: Optional[List[str]] = None
    wiki: Optional[str] = None
    created_at: Optional[datetime] = Field(default=None, alias="createdAt")
    modified_at: Optional[datetime] = Field(default=None, alias="lastModifiedAt")
    func_sql: Optional[str] = Field(default=None, alias="functionSql")
    owner: Optional[EnterpriseSearchUserOrRoleObject] = None

    def as_df_dict(self):
        return {
            "path": self.path,
            "name": ".".join(f'"{p}"' for p in self.path),
            "type": self.type,
            "tags": ",".join(self.labels),
            "description": self.wiki,
        }


class EnterpriseSearchResultsObject(BaseModel):
    category: Optional[Category] = None
    job: Optional[EnterpriseSearchJobObject] = Field(default=None, alias="jobObject")
    script: Optional[EnterpriseSearchScriptObject] = Field(
        default=None, alias="scriptObject"
    )
    reflection: Optional[EnterpriseSearchReflectionObject] = Field(
        default=None, alias="reflectionObject"
    )
    catalog: Optional[EnterpriseSearchCatalogObject] = Field(
        default=None, alias="catalogObject"
    )


class EnterpriseSearchResults(BaseModel):
    session_id: Optional[str] = Field(default=None, alias="sessionId")
    next_page_token: Optional[str] = Field(default=None, alias="nextPageToken")
    results: Optional[List[EnterpriseSearchResultsObject]] = Field(default_factory=list)
    error: Optional[str] = Field(default=None, alias="errorMessage")
    more: Optional[str] = Field(default=None, alias="moreInfo")


class Search(BaseModel):
    max_results: Optional[int] = Field(default=50, alias="maxResults")
    next_page_token: Optional[str] = Field(default=None, alias="pageToken")
    filter: Optional[Union[str, List[Category]]] = ""
    query: str = None

    @field_validator("filter", mode="after")
    @classmethod
    def validate_filter(cls, v: Union[str, List[Category]]) -> str:
        if isinstance(v, str) and v:
            v = f'category in ["{Category[v.upper()].name}"]'
        elif isinstance(v, list):
            v = ",".join([f'"{c.name}"' for c in v if isinstance(c, Category)])
            v = f"category in [{v}]"
        else:
            v = ""
        return v

    model_config: ConfigDict = ConfigDict(serialize_by_alias=True)


class EnterpriseSearchResultsWrapper(BaseModel):
    results: List[EnterpriseSearchResultsObject] = Field(default_factory=list)


class CatalogSearchApiResponse(BaseModel):
    """Shape of ``GET /api/v3/catalog/search`` (Dremio OSS)."""

    data: List[Dict[str, Any]] = Field(default_factory=list)


def _oss_dataset_row_kind(dataset_type: str | None) -> str | None:
    """Map Dremio ``datasetType`` to TABLE / VIEW for agent discovery."""
    if not dataset_type:
        return None
    u = dataset_type.upper()
    if u == "VIRTUAL":
        return "VIEW"
    if u in ("PROMOTED", "DIRECT"):
        return "TABLE"
    return None


def _oss_filter_wants_view_only(filter_str: str) -> bool | None:
    """Interpret enterprise-style filter string from :class:`Search`."""
    f = filter_str or ""
    if '["VIEW"]' in f:
        return True
    if '["TABLE"]' in f:
        return False
    return None


def _oss_tags_to_str(tags: Any) -> str:
    if tags is None:
        return ""
    if isinstance(tags, dict):
        lst = tags.get("tags") or tags.get("labels")
        if isinstance(lst, list):
            return ",".join(str(x) for x in lst)
    return str(tags)


async def _get_search_results_oss_catalog(
    search: Search,
    use_df: bool,
    remove_catalog_name: Optional[bool],
) -> EnterpriseSearchResultsWrapper | pd.DataFrame:
    """On-prem Dremio has no ``POST /api/v3/search``; use catalog text search instead."""
    # #region agent log
    import time as _dbg_time

    _t0 = _dbg_time.perf_counter()
    # #endregion
    del remove_catalog_name  # not applicable to catalog search API
    client = AsyncHttpClient()
    _q = search.query or ""
    resp = await client.get(
        "/api/v3/catalog/search",
        params={"query": _q},
        deser=CatalogSearchApiResponse,
    )
    # #region agent log
    _t_after_http = _dbg_time.perf_counter()
    try:
        import json as _dbg_json

        with open(
            "/home/djuybu/thesis/.cursor/debug-a789f9.log",
            "a",
            encoding="utf-8",
        ) as _df:
            _df.write(
                _dbg_json.dumps(
                    {
                        "sessionId": "a789f9",
                        "hypothesisId": "H2,H5",
                        "location": "search.py:_get_search_results_oss_catalog",
                        "message": "after catalog/search GET",
                        "data": {
                            "query_len": len(_q),
                            "query_preview": _q[:120],
                            "api_raw_hits": len(resp.data or []),
                            "use_df": use_df,
                            "filter": (search.filter or "")[:80],
                            "http_ms": round((_t_after_http - _t0) * 1000, 2),
                        },
                        "timestamp": int(_dbg_time.time() * 1000),
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
    except Exception:
        pass
    # #endregion
    want_view = _oss_filter_wants_view_only(search.filter or "")

    rows: list[dict[str, Any]] = []
    for item in resp.data:
        if (item.get("type") or "").upper() != "DATASET":
            continue
        kind = _oss_dataset_row_kind(item.get("datasetType"))
        if kind is None:
            continue
        if want_view is True and kind != "VIEW":
            continue
        if want_view is False and kind != "TABLE":
            continue
        path = item.get("path")
        if not path or not isinstance(path, list):
            continue
        name = ".".join(f'"{p}"' for p in path)
        rows.append(
            {
                "path": path,
                "name": name,
                "type": kind,
                "tags": _oss_tags_to_str(item.get("tags")),
                "description": "",
            }
        )

    cap = search.max_results if search.max_results is not None else 50
    cap = max(1, min(cap, 500))
    if len(rows) > cap:
        rows = rows[:cap]

    if use_df:
        if not rows:
            return pd.DataFrame(
                columns=["path", "name", "type", "tags", "description", "schema"]
            )
        paths = [r["path"] for r in rows]
        # #region agent log
        _t_before_schema = _dbg_time.perf_counter()
        # #endregion
        if schemas := await get_schemas(paths, include_tags=True, flatten=True):
            for ix, schema in enumerate(schemas):
                if ix < len(rows):
                    rows[ix]["schema"] = schema.get("schema")
        # #region agent log
        _t_end = _dbg_time.perf_counter()
        try:
            import json as _dbg_json

            with open(
                "/home/djuybu/thesis/.cursor/debug-a789f9.log",
                "a",
                encoding="utf-8",
            ) as _df:
                _df.write(
                    _dbg_json.dumps(
                        {
                            "sessionId": "a789f9",
                            "hypothesisId": "H2",
                            "location": "search.py:_get_search_results_oss_catalog",
                            "message": "after get_schemas batch",
                            "data": {
                                "filtered_dataset_rows": len(rows),
                                "schema_batch_ms": round(
                                    (_t_end - _t_before_schema) * 1000, 2
                                ),
                                "total_oss_path_ms": round((_t_end - _t0) * 1000, 2),
                            },
                            "timestamp": int(_dbg_time.time() * 1000),
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
        except Exception:
            pass
        # #endregion
        return pd.DataFrame(data=rows)

    wrapped: list[EnterpriseSearchResultsObject] = []
    for r in rows:
        cat = Category.VIEW if r["type"] == "VIEW" else Category.TABLE
        labels = [x.strip() for x in r["tags"].split(",") if x.strip()]
        wrapped.append(
            EnterpriseSearchResultsObject(
                category=cat,
                catalog=EnterpriseSearchCatalogObject(
                    path=r["path"],
                    type=r["type"],
                    labels=labels or None,
                    wiki=r["description"] or None,
                ),
            )
        )
    return EnterpriseSearchResultsWrapper(results=wrapped)


async def get_search_results(
    search: str | Search, use_df: bool = False,
    remove_catalog_name: Optional[bool] = True
) -> EnterpriseSearchResultsWrapper | pd.DataFrame:
    if isinstance(search, str):
        search = Search(query=search)

    # Dremio Cloud uses project search; OSS exposes GET /api/v3/catalog/search only.
    if not settings.instance().dremio.project_id:
        return await _get_search_results_oss_catalog(search, use_df, remove_catalog_name)

    client = AsyncHttpClient()
    endpoint = (
        f"/v0/projects/{settings.instance().dremio.project_id}/search"
        if settings.instance().dremio.project_id
        else "/api/v3/search"
    )

    params = {"removeCatalogName": str(remove_catalog_name).lower()}

    result = []
    response = await client.post(
        endpoint,
        body=search.model_dump(exclude_none=True),
        deser=EnterpriseSearchResults,
        params=params,
    )
    while response.results and response.error is None and response.more is None:
        result.extend(response.results)
        if response.next_page_token is None:
            break
        search.next_page_token = response.next_page_token
        response = await client.post(
            endpoint,
            body=search.model_dump(exclude_none=True),
            deser=EnterpriseSearchResults,
            params=params,
        )

    if use_df:
        result = [r for r in result if r.category in (Category.TABLE, Category.VIEW)]

        data = [r.catalog.as_df_dict() for r in result]
        paths = [p["path"] for p in data]
        if schemas := await get_schemas(paths, include_tags=True, flatten=True):
            for ix, schema in enumerate(schemas):
                data[ix]["schema"] = schema.get("schema")

        return pd.DataFrame(data=data)

    return EnterpriseSearchResultsWrapper(results=result)
