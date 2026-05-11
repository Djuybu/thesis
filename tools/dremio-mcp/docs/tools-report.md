# Dremio MCP Tools -- Client Report

## What Is This Document?

This report describes every tool provided by the **Dremio MCP Server**. The MCP (Model Context Protocol) server acts as a bridge between an AI assistant (such as Claude) and your Dremio data platform. Each "tool" is a specific capability the AI can use to retrieve information from or interact with your Dremio environment.

For each tool you will find:

- **Name** -- the exact identifier used when the tool is called.
- **Description** -- a plain-language explanation of what the tool does.
- **Input** -- what information (if any) must be provided to the tool.
- **Desired Output** -- what the tool returns and what it means.

---

## Server Modes

The server can run in different modes. Each mode makes a different subset of tools available. Modes can be combined.

| Mode | Purpose |
|------|---------|
| `FOR_SELF` | Inspect the Dremio cluster itself -- jobs, engines, system tables, usage. |
| `FOR_DATA_PATTERNS` | Explore user data -- tables, schemas, lineage, search. |
| `FOR_PROMETHEUS` | Query monitoring metrics from a Prometheus instance connected to Dremio. |
| `EXPERIMENTAL` | Tools still under development (currently: semantic search). Requires `enable_search: true` in config. |

---

## How to Run Tools (Command Line)

Although tools are normally called by the AI assistant automatically, they can also be tested from the command line.

**List available tools for a mode:**

```bash
uv run dremio-mcp-server tools list -m FOR_SELF
```

**Run a specific tool with arguments:**

```bash
uv run dremio-mcp-server tools invoke -t RunSqlQuery -c config.yaml \
    "query=SELECT * FROM sys.nodes"
```

Arguments are passed in the format `argument_name=value`.

---

## Tools Reference

### 1. GetFailedJobDetails

| | |
|---|---|
| **Description** | Retrieves statistics and details of all failed or canceled jobs that ran on the Dremio cluster during the past 7 days, broken down by job type, engine, user, dataset, and error message. |
| **Available in modes** | `FOR_SELF` |

**Input**

No input required. The tool automatically queries the last 7 days.

**Desired Output**

A report containing:

| Output field | Meaning |
|---|---|
| Number of jobs over 7 days | Total count of failed/canceled jobs in the period. |
| Job categories by day, queryType and state | Daily breakdown of job counts grouped by the type of query and its final state (FAILED or CANCELED). |
| Job count by day, queryType and engine | Daily breakdown further split by which compute engine ran the job. |
| Job count by day, queryType, user | Daily breakdown further split by which user submitted the job. |
| Job count by day, queriedDataset and state | Daily breakdown by which dataset was queried and the job outcome. |
| Job count by day, queryType and error | Daily breakdown with the specific error message for each failure. |

---

### 2. RunSqlQuery

| | |
|---|---|
| **Description** | Executes a SQL query on the Dremio cluster and returns the results. By default only SELECT queries are allowed; data-modifying statements (INSERT, UPDATE, DELETE, etc.) are blocked unless explicitly enabled in the server configuration. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `query` | text | Yes | The SQL query to execute. Reserved words used as identifiers (e.g. `day`, `count`, `table`) must be enclosed in double quotes. |

**Desired Output**

A dictionary with a `result` key containing a list of row objects. Each row object is a set of key-value pairs corresponding to the columns returned by the query.

Example:

```json
{
  "result": [
    { "node_id": "node1", "status": "UP" },
    { "node_id": "node2", "status": "UP" }
  ]
}
```

**Constraints:**
- DML statements (INSERT, UPDATE, DELETE, DROP, etc.) are rejected unless `allow_dml` is set to `true` in the server config.
- If a query is not allowed, an error message is returned instead of results.

---

### 3. BuildUsageReport

| | |
|---|---|
| **Description** | Generates a usage report for the Dremio project over the past 7 days, grouped by either compute engines or projects. Useful for understanding resource consumption and workload distribution. |
| **Available in modes** | `FOR_SELF` |
| **Requirement** | A Dremio Cloud `project_id` must be configured. This tool is not available for software (on-premise) deployments without a project ID. |

**Input**

| Argument | Type | Required | Default | Description |
|---|---|---|---|---|
| `by` | text | No | `"ENGINE"` | Grouping dimension. Accepted values: `"ENGINE"` or `"PROJECT"`. |

**Desired Output**

A list of usage records. Each record contains resource consumption metrics for the chosen grouping (engine name or project) over the 7-day window.

---

### 4. GetNameOfJobsRecentTable

| | |
|---|---|
| **Description** | Returns the fully qualified name of the system table that stores recent job execution history. This is a quick-reference tool so the AI knows the correct table name before building queries. |
| **Available in modes** | `FOR_SELF` |

**Input**

No input required.

**Desired Output**

A dictionary with a single key:

| Output field | Value |
|---|---|
| `name` | `sys.project.jobs_recent` |

---

### 5. GetUsefulSystemTableNames

| | |
|---|---|
| **Description** | Lists the most important system tables available in the Dremio cluster, along with a brief explanation of what each table contains. Useful as a starting point before writing queries against system metadata. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS` |

**Input**

No input required.

**Desired Output**

A dictionary where each key is a system table name and each value is a short description:

| Table name | Description |
|---|---|
| `INFORMATION_SCHEMA."TABLES"` | Information about tables in the cluster. Filter out SYSTEM_TABLE to see user tables. |
| `sys.project.jobs_recent` | Recent job execution history including status, duration, user, and error details. |
| `sys.project.engines` | Engine configuration and status for the project. |
| `sys.organization.users` | Organization user information. |
| `INFORMATION_SCHEMA."COLUMNS"` | Column-level metadata for all tables and views. |
| `INFORMATION_SCHEMA."VIEWS"` | View definitions and metadata. |

---

### 6. GetSchemaOfTable

| | |
|---|---|
| **Description** | Retrieves the schema (column names, data types, and optional descriptions/tags) of a given table or view in the Dremio cluster. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `table_name` | text or list | Yes | The fully qualified table name. Can be provided as a dot-separated string (e.g. `"source"."schema"."table"`) or as a list of path components (e.g. `["source", "schema", "table"]`). |

**Desired Output**

A dictionary containing:

| Output field | Meaning |
|---|---|
| `fields` | A list of objects, each describing one column with its name and data type. |
| `text` (optional) | Additional descriptive text about the table, if available. |
| `tags` (optional) | Tags/labels attached to the table in Dremio. |

---

### 7. GetTableOrViewLineage

| | |
|---|---|
| **Description** | Traces the lineage (upstream data sources) of a given table or view. Useful for understanding where data originates and how it flows through transformations. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `table_name` | text or list | Yes | The fully qualified name of the table or view, including the schema. Special characters in names should be quoted. |

**Desired Output**

A JSON structure representing the lineage graph of the table or view -- showing which source tables and intermediate views feed into it.

---

### 8. SearchTableAndViews

| | |
|---|---|
| **Description** | Performs a semantic search across the Dremio cluster to find tables and views that match a natural-language query. Returns matching items along with their full schemas, so no follow-up schema lookup is needed. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS`, `EXPERIMENTAL` |
| **Requirement** | `enable_search` must be set to `true` in the server configuration. |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `query` | text | Yes | A natural-language search query describing the data you are looking for (e.g. "customer orders by region"). |

**Desired Output**

A dictionary with a `results` key containing a list of matching objects. Each object includes:

| Output field | Meaning |
|---|---|
| `name` | Fully qualified name of the table or view. |
| `type` | Either `TABLE` or `VIEW`. |
| `tags` | Tags associated with the item. |
| `description` | Description text, if any. |
| `schema` | Full column schema of the table or view. |

---

### 9. GetDescriptionOfTableOrSchema

| | |
|---|---|
| **Description** | Retrieves the human-readable description and tags for one or more tables or schemas. Also returns descriptions of parent schemas in the hierarchy. Useful for understanding what a dataset represents before querying it. |
| **Available in modes** | `FOR_SELF`, `FOR_DATA_PATTERNS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `name` | text or list | Yes | A single table/schema name (text) or a list of names. |

**Desired Output**

A dictionary where each key is a part of the table or schema hierarchy and each value is an object with `description` and `tags` fields.

---

### 10. GetRelevantMetrics

| | |
|---|---|
| **Description** | Returns a curated list of the most relevant Prometheus metric names for monitoring a Dremio cluster, along with a description of what each metric measures. |
| **Available in modes** | `FOR_PROMETHEUS` |

**Input**

No input required.

**Desired Output**

A dictionary of metric names and their descriptions:

| Metric name | What it measures |
|---|---|
| `jobs_total` | Total number of jobs executed. |
| `jobs_failed_total` | Total number of failed jobs. |
| `jobs_command_pool_queue_size` | Number of jobs queued before planning begins. |
| `jvm_gc_pause_seconds` | Duration of JVM garbage-collection pauses (also indicates system activity). |
| `memory_heap_usage` | JVM heap memory in use. |
| `memory_heap_committed` | JVM heap memory committed. |
| `dremio_engine_executors` | Number of executors running in a Dremio engine. |
| `dremio_engine_replica_running` | Number of running replicas in a Dremio engine. |

---

### 11. GetMetricSchema

| | |
|---|---|
| **Description** | Given a Prometheus metric name, returns all the labels (dimensions) available for that metric along with a sample value for each label. Helps in constructing precise monitoring queries. |
| **Available in modes** | `FOR_PROMETHEUS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `metric` | text | Yes | The name of the Prometheus metric (e.g. `jobs_total`). |

**Desired Output**

A dictionary where each key is a label name and each value is a sample value for that label.

---

### 12. RunPromQL

| | |
|---|---|
| **Description** | Executes a PromQL (Prometheus Query Language) query against the connected Prometheus instance. The query automatically covers the last 7 days with a 1-hour step interval. |
| **Available in modes** | `FOR_PROMETHEUS` |

**Input**

| Argument | Type | Required | Description |
|---|---|---|---|
| `promql_query` | text | Yes | A valid PromQL expression (e.g. `rate(jobs_total[1h])`). |

**Desired Output**

A list of time-series records. Each record contains the metric labels and a set of timestamp-value pairs representing the metric over the 7-day window.

---

## Resources

In addition to tools, the server exposes one **resource** -- a static piece of reference information the AI can read at any time.

### Hints

| | |
|---|---|
| **Description** | Provides guidance on key dimensions that can be used to analyze and optimize the Dremio cluster, such as job counts, failure rates, and overall system usage. |
| **Available in modes** | `FOR_SELF` |
| **URI** | `dremio://hints` |

**Input**

No input required.

**Desired Output**

A text description of the key analysis dimensions available in the Dremio cluster.

---

## Quick Reference Summary

| # | Tool Name | Modes | Input | Output summary |
|---|---|---|---|---|
| 1 | GetFailedJobDetails | FOR_SELF | None | Failed/canceled job statistics over 7 days |
| 2 | RunSqlQuery | FOR_SELF, FOR_DATA_PATTERNS | `query` (SQL text) | Query result rows |
| 3 | BuildUsageReport | FOR_SELF | `by` (ENGINE or PROJECT) | Usage records grouped by engine or project |
| 4 | GetNameOfJobsRecentTable | FOR_SELF | None | System table name for job history |
| 5 | GetUsefulSystemTableNames | FOR_SELF, FOR_DATA_PATTERNS | None | List of important system tables with descriptions |
| 6 | GetSchemaOfTable | FOR_SELF, FOR_DATA_PATTERNS | `table_name` | Column names, types, and optional tags |
| 7 | GetTableOrViewLineage | FOR_SELF, FOR_DATA_PATTERNS | `table_name` | Data lineage graph |
| 8 | SearchTableAndViews | FOR_SELF, FOR_DATA_PATTERNS, EXPERIMENTAL | `query` (search text) | Matching tables/views with schemas |
| 9 | GetDescriptionOfTableOrSchema | FOR_SELF, FOR_DATA_PATTERNS | `name` (one or more names) | Descriptions and tags for tables/schemas |
| 10 | GetRelevantMetrics | FOR_PROMETHEUS | None | Curated list of Dremio Prometheus metrics |
| 11 | GetMetricSchema | FOR_PROMETHEUS | `metric` (metric name) | Labels and sample values for one metric |
| 12 | RunPromQL | FOR_PROMETHEUS | `promql_query` (PromQL expression) | Time-series data over 7 days |
| -- | **Hints** (resource) | FOR_SELF | None | Guidance text on cluster analysis dimensions |

---

## Key Constraints and Safety Rules

1. **SQL safety** -- By default, `RunSqlQuery` only allows SELECT queries. Data-modifying statements (INSERT, UPDATE, DELETE, DROP, ALTER, etc.) are blocked. This can be overridden by setting `allow_dml: true` in the server configuration file.

2. **Mode-based availability** -- A tool is only accessible when the server is running in a mode that includes that tool. For example, Prometheus tools are only available when `FOR_PROMETHEUS` is part of the configured `server_mode`.

3. **Project ID requirement** -- `BuildUsageReport` requires a Dremio Cloud `project_id` to be configured. It will not appear in the available tools list for on-premise deployments without one.

4. **Experimental gating** -- `SearchTableAndViews` is marked `EXPERIMENTAL` and will only be available when `enable_search: true` is set in the configuration.

5. **Authentication** -- When running in streaming HTTP mode (remote deployments), all tool calls are authenticated via bearer tokens. In local (stdio) mode, authentication is handled by the configured Personal Access Token (PAT).

6. **Read-only by default** -- All tools are annotated as read-only except `RunSqlQuery` when DML is explicitly enabled, in which case it is marked as potentially destructive.
