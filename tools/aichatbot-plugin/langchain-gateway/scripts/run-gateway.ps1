# Start the LangChain HTTP gateway (Ollama + MCP via aichatbot mcp-proxy).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$gw = Resolve-Path (Join-Path $here "..")
Set-Location $gw

$py = Join-Path $gw ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
  Write-Error "Missing $py — run scripts\setup-venv.ps1 first."
  exit 1
}

$env:GATEWAY_HOST = if ($env:GATEWAY_HOST) { $env:GATEWAY_HOST } else { "127.0.0.1" }
$env:GATEWAY_PORT = if ($env:GATEWAY_PORT) { $env:GATEWAY_PORT } else { "9292" }
$env:OLLAMA_MODEL = if ($env:OLLAMA_MODEL) { $env:OLLAMA_MODEL } else { "qwen2.5:3b" }
$env:OLLAMA_BASE_URL = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { "http://127.0.0.1:11434" }
$env:AICHAT_MCP_PROXY_URL = if ($env:AICHAT_MCP_PROXY_URL) { $env:AICHAT_MCP_PROXY_URL } else { "http://127.0.0.1:9191/aichat/mcp-proxy?path=/mcp" }

Write-Host "Gateway http://$($env:GATEWAY_HOST):$($env:GATEWAY_PORT)  MCP proxy: $($env:AICHAT_MCP_PROXY_URL)  Ollama model: $($env:OLLAMA_MODEL)"
& $py -m uvicorn gateway_app:app --host $env:GATEWAY_HOST --port $env:GATEWAY_PORT
