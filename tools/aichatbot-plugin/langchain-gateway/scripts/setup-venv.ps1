# Run from repository root (dremio-oss) or from this script's directory.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$gw = Resolve-Path (Join-Path $here "..")
Set-Location $gw

if (-not (Test-Path ".venv")) {
  python -m venv .venv
}
& (Join-Path $gw ".venv\Scripts\pip.exe") install -r requirements.txt
Write-Host "venv ready at $gw\.venv — activate: .\.venv\Scripts\Activate.ps1"
