# Framework Integrations (Experimental)

The Dremio MCP server provides integrations with popular AI agent frameworks, allowing you to use the same MCP tools with different agentic frameworks. Currently supported frameworks are [LangChain](https://python.langchain.com/) and [BeeAI](https://github.com/i-am-bee/beeai-framework). BeeAI framework already supports MCP.

## Overview

The frameworks integration enables:

-   Reuse of MCP tools across different agent frameworks
-   Consistent tool behavior regardless of the framework
-   Framework-specific optimizations and features

## LangChain Integration

### Features

-   Automatic tool discovery and registration
-   Integration with LangChain's ReAct agent
-   Support for multiple LLM providers (OpenAI, Ollama)
-   Structured tool responses

### Usage Example

```shell
# Runs the LangChain server with all `tools` preconfigured in a simple
# commandline interactive loop
$ uv run python -m dremioai.servers.frameworks.langchain.server
```

Programmatic usage can be looked at in the [src/dremioai/servers/frameworks/langchain/server.py]

### Configuration

Configure LangChain settings in your config file as mentioned in [settings](settings.md#langchain-settings-experimental)

## BeeAI Integration

### Features

-   MCP server integration with BeeAI agents
-   Sliding memory window support
-   Multiple LLM provider support
-   Environment variable management

### Usage Example

```shell
# Runs the BeeAI server with the MCP server on the commandline in an interactive loop
$ uv run python -m dremioai.servers.frameworks.beeai.server
```

Programmatic usage can be looked at in the [src/dremioai/servers/frameworks/beeai/server.py]

### Configuration

Configure BeeAI settings in your config file as mentioned in [settings](settings.md#beeai-settings-experimental)

## See Also

-   [Tools Documentation](tools.md)
-   [Settings Configuration](settings.md)
-   [Architecture Overview](architecture.md)
