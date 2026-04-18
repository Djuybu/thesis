# Dremio Tools Package

## Overview

The `dremioai.tools` package provides a collection of tools for interacting with and analyzing Dremio clusters. These tools are designed to work with the Model Context Protocol (MCP) server and can also be integrated with other LLM frameworks if needed.

## Tool Types

Tools are categorized into different types using the `ToolType` enum:

| Tool Type           | Description                                                                |
| ------------------- | -------------------------------------------------------------------------- |
| `FOR_SELF`          | Tools for introspecting Dremio cluster and its usage patterns              |
| `FOR_PROMETHEUS`    | Tools supporting Prometheus stack setup in conjunction with Dremio         |
| `FOR_DATA_PATTERNS` | Tools for discovering data patterns analysis using Dremio's semantic layer |
| `EXPERIMENTAL`      | Experimental tools not yet ready for production                            |

## Core Classes

### Tools Base Class

The `Tools` class serves as the base class for all tool implementations. Key features:

-   Handles Dremio connection settings (URI, PAT, project ID)
-   Provides interface for tool implementation
-   Supports LangChain compatibility

### Available Tools

| Category               | Tool Name                   | Description                                            |
| ---------------------- | --------------------------- | ------------------------------------------------------ |
| **Cluster Analysis**   | `GetFailedJobDetails`       | Analyzes failed/canceled jobs over the past 7 days     |
|                        | `BuildUsageReport`          | Generates usage reports grouped by engines or projects |
|                        | `GetRelevantMetrics`        | Retrieves Prometheus metrics for the Dremio cluster    |
| **SQL and Data**       | `RunSqlQuery`               | Executes SELECT queries on the Dremio cluster          |
|                        | `GetSchemaOfTable`          | Retrieves schema information for tables                |
|                        | `GetTableOrViewLineage`     | Finds lineage of tables/views                          |
|                        | `SemanticSearch`            | Performs semantic search across the cluster            |
| **System Information** | `GetNameOfJobsRecentTable`  | Returns the system table name for job information      |
|                        | `GetUsefulSystemTableNames` | Lists important system tables                          |
|                        | `GetMetricSchema`           | Returns metric labels and sample values                |
|                        | `RunPromQL`                 | Executes Prometheus queries                            |

## Usage (for testing)

While the tools are invoked directly from the LLM, it provides a command-line interface for testing and debugging. Tools can be tested directly from the command line using the `dremio-mcp-server tools` commands:

### List Available Tools

To see all available tools for a specific mode:

```bash
$ uv run dremio-mcp-server tools list -m FOR_SELF
┏━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━┓
┃ Tool                  ┃ Description                                  ┃ For      ┃
┡━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━┩
│ GetFailedJobDetails   │ Analyzes failed/canceled jobs over past 7d   │ FOR_SELF │
│ BuildUsageReport      │ Generates usage reports by engines/projects  │ FOR_SELF │
│ RunSqlQuery           │ Executes SELECT queries on Dremio cluster    │ FOR_SELF │
└───────────────────────┴──────────────────────────────────────────────┴──────────┘
```

### Execute a Specific Tool

To test a specific tool with arguments:

```bash
$ uv run dremio-mcp-server tools invoke -t RunSqlQuery -c config.yaml \
         args="query=SELECT * FROM sys.nodes"
[
  {
    "node_id": "node1",
    "node_type": "COORDINATOR",
    "status": "UP",
    "last_contact": "2024-01-20 10:30:00"
  },
  {
    "node_id": "node2",
    "node_type": "EXECUTOR",
    "status": "UP",
    "last_contact": "2024-01-20 10:29:55"
  }
]
```

Arguments are passed in the format `arg=value`. Multiple arguments can be provided:

## Tool Development

To create a new tool:

1. Inherit from the `Tools` class
2. Specify the tool type using the `For` class variable
3. Implement the `invoke()` method
4. Add parameter specifications if needed

Example:

```python
from typing import ClassVar, Annotated
from dremioai.config.tools import ToolType

class MyNewTool(Tools):
    For: ClassVar[Annotated[ToolType, ToolType.FOR_SELF]]

    async def invoke(self) -> Dict[str, Any]:
        """Tool description here"""
        # Implementation
        pass
```

## Integration Support

The tools package supports integration with:

-   MCP Server
-   LangChain
-   Other frameworks through the standard tool interface

## Resource Tools

A special category of tools inheriting from the `Resource` class provides access to static resources and documentation within the Dremio environment.

## Error Handling

Tools implement proper error handling for:

-   Invalid SQL queries
-   Missing permissions
-   Connection issues
-   Invalid parameters

## Configuration

Tools can be configured through:

-   Direct initialization parameters
-   Environment variables
-   Configuration files

For detailed configuration options, refer to the settings documentation.
