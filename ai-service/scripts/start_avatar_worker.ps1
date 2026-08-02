[CmdletBinding()]
param(
    [string]$EnvFile = "",
    [string]$HostAddress = "0.0.0.0",
    [int]$Port = 8610,
    [int]$MaxConcurrency = 1,
    [string]$Stages = "voice,scene,lip_sync,stt,postprocess"
)

$ErrorActionPreference = "Stop"
$serviceRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $serviceRoot ".env.avatar-local.generated"
}
if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Avatar worker environment file was not found: $EnvFile"
}

$python = Join-Path $serviceRoot ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    throw "AI service virtual environment was not found: $python"
}

$env:AVATAR_WORKER_ENV_FILE = (Resolve-Path -LiteralPath $EnvFile).Path
$env:AVATAR_GPU_MAX_CONCURRENCY = [string][Math]::Max(1, $MaxConcurrency)
$env:AVATAR_WORKER_STAGES = $Stages

Push-Location $serviceRoot
try {
    & $python -m uvicorn avatar_worker_main:app `
        --host $HostAddress `
        --port $Port `
        --no-access-log
} finally {
    Pop-Location
}
