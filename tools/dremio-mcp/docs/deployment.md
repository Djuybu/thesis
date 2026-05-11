# Dremio SQL Agent — Deployment & Operations Guide

Hướng dẫn chạy ngắn (tiếng Việt): [RUN-VI.md](RUN-VI.md).

## Architecture Overview

```
DAC UI  →  DAC Backend (/aichat/* proxy)  →  SQL Agent Gateway (port 9292)
                                                   ↓
                                              LangGraph StateGraph
                                              (guardrail → discovery → metadata_confirm →
                                               sql_gen → refinement → execute)
                                                   ↓
                                              Dremio MCP Server (port 8080)
                                                   ↓
                                              Dremio Cluster (port 9047)
```

## Prerequisites

- **Python 3.11 or newer** (required by `dremioai` and `beeai-framework`). On Python 3.10, `pip install` fails with `No matching distribution found for beeai-framework` — upgrade Python or use `uv python install 3.12`.
- Use a **separate venv under `tools/dremio-mcp`** (do not reuse `tools/aichatbot-plugin/langchain-gateway/.venv`).
- Ollama with a chat model pulled (e.g. `ollama pull qwen3.5:4b`)
- Dremio running (port 9047)
- dremio-mcp server running with `--enable-streaming-http`

## Quick Start (Local)

```bash
# 1. Start Dremio
distribution/server/target/dremio-community-*/bin/dremio start

# 2. Start Ollama
ollama serve &
ollama pull qwen3.5:4b

# 3. Start dremio-mcp server (subcommand: run; config: -c/--cfg, not --config)
cd tools/dremio-mcp
uv run dremio-mcp-server run \
  -c local/mcp-oss.yaml \
  --enable-streaming-http \
  --port 8080 &

# 4. Start SQL Agent Gateway
pip install -e .
dremio-sql-agent
```

## Environment Variables

### Gateway

| Variable | Default | Description |
|----------|---------|-------------|
| `GATEWAY_HOST` | `127.0.0.1` | Bind host |
| `GATEWAY_PORT` | `9292` | Bind port |
| `OLLAMA_MODEL` | `qwen3.5:4b` | Default chat model |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | Ollama API URL |
| `DREMIO_MCP_URL` | `http://127.0.0.1:8080/mcp/` | MCP server URL |
| `DREMIO_MCP_TIMEOUT_SECONDS` | `120` | MCP request timeout |
| `DREMIO_MCP_SSE_READ_TIMEOUT_SECONDS` | `7200` | SSE read timeout |

### Dremio config

Set in `conf/dremio.conf` or via env:

```
DREMIO_AICHATBOT_PLUGIN_BASE_URL=http://127.0.0.1:9292
```

## Health Check

```bash
curl http://127.0.0.1:9292/aichat/health
# {"status":"ok","service":"dremio-sql-agent"}
```

## Smoke Test

```bash
# Get config
curl http://127.0.0.1:9292/aichat/v1/config

# Start a chat (requires valid Dremio bearer token)
curl -X POST http://127.0.0.1:9292/aichat/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_DREMIO_TOKEN" \
  -d '{"message": "What tables do I have?"}'

# Resume after HITL interrupt
curl -X POST http://127.0.0.1:9292/aichat/v1/chat/resume \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_DREMIO_TOKEN" \
  -d '{"thread_id": "hitl-xxx", "action": "approve"}'
```

## Rollback Procedure

If the new gateway has issues, switch back to the legacy plugin temporarily:

1. Update `conf/dremio.conf`:
   ```
   services.coordinator.web.aichatbot.plugin.base_url = "http://127.0.0.1:9191"
   ```
2. Start the legacy plugin JAR:
   ```bash
   java -jar tools/aichatbot-plugin/target/dremio-aichatbot-plugin-*.jar
   ```
3. Restart Dremio to pick up the config change.

## Monitoring

The gateway logs to stdout (structured via Python `logging`).
Key log messages to watch:

- `Loaded N tools from Dremio MCP` — successful MCP connection
- `Agent graph failed` — graph execution error
- `Failed to load MCP tools` — MCP connection failure

## Go-Live Checklist

- [ ] Ollama running with desired model pulled
- [ ] dremio-mcp server running with `--enable-streaming-http`
- [ ] Gateway health check returns 200
- [ ] `conf/dremio.conf` base_url points to gateway (port 9292)
- [ ] DAC UI can reach `/aichat/v1/config` and `/aichat/v1/chat`
- [ ] HITL flow works: start → interrupt (metadata) → approve → interrupt (SQL) → approve → result
- [ ] Guardrail blocks non-data requests
- [ ] Legacy plugin JAR available for rollback if needed
