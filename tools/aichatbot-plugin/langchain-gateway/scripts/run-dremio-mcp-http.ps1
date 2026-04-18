# Example: run dremio-mcp in streaming HTTP mode (clone repo first; see README).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $here "..\..\..\..")
$mcpRoot = Join-Path $repoRoot "tools\dremio-mcp"

if (-not (Test-Path $mcpRoot)) {
  Write-Error "Expected dremio-mcp at $mcpRoot — git clone https://github.com/dremio/dremio-mcp.git tools\dremio-mcp"
  exit 1
}

$config = if ($args.Count -ge 1) { $args[0] } else {
  Join-Path $here "..\example-mcp-oss.yaml"
}
$config = Resolve-Path $config

Write-Host "Using config: $config"
Write-Host "From $mcpRoot run (with uv installed):"
Write-Host "  uv run dremio-mcp-server run -c `"$config`" --enable-streaming-http --port 8000 --host 127.0.0.1"
Set-Location $mcpRoot
if (Get-Command uv -ErrorAction SilentlyContinue) {
  uv run dremio-mcp-server run -c $config --enable-streaming-http --port 8000 --host 127.0.0.1
} else {
  Write-Error "Install uv (https://docs.astral.sh/uv/) or run the printed command manually from tools\dremio-mcp."
  exit 1
}
