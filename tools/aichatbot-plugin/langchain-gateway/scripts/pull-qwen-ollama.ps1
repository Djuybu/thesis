# Pulls a small Qwen model for local testing (requires Ollama installed).
$ErrorActionPreference = "Stop"
$model = if ($env:OLLAMA_MODEL) { $env:OLLAMA_MODEL } else { "qwen2.5:3b" }

if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
  Write-Host "Ollama not found on PATH. Install from https://ollama.com then run:" -ForegroundColor Yellow
  Write-Host "  ollama pull $model"
  exit 1
}

Write-Host "Pulling $model ..."
ollama pull $model
Write-Host "Done. Default gateway model is $model (override with OLLAMA_MODEL)."
