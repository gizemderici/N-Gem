[CmdletBinding()]
param(
    [switch]$ResetData,
    [switch]$ConfirmReset,
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 native bir komutun stderr'ine yazdigi her satiri
# ErrorRecord'a sariyor ve $ErrorActionPreference = "Stop" altinda betigi
# oldurüyor. `docker compose` ilerleme ciktisini stderr'e yazdigi icin bu
# betik basarili bir kurulumda bile duruyordu. Cozum stderr'i degil, cikis
# kodunu olcmek.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][scriptblock]$Command
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Command 2>&1 | ForEach-Object { Write-Host $_ }
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Description basarisiz oldu (cikis kodu $LASTEXITCODE)."
    }
}

$backendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $backendRoot "docker-compose.yml"
$seedFile = Join-Path $backendRoot "seed\seed_demo.py"

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "Beklenen docker-compose.yml bulunamadi: $composeFile"
}
if (-not (Test-Path -LiteralPath $seedFile -PathType Leaf)) {
    throw "Beklenen demo seed betigi bulunamadi: $seedFile"
}
if ($ResetData -and -not $ConfirmReset) {
    throw "Reset PostgreSQL ve MinIO demo volume'lerini siler. Onay icin -ResetData -ConfirmReset kullanin."
}
if ($ConfirmReset -and -not $ResetData) {
    throw "-ConfirmReset yalnizca -ResetData ile birlikte kullanilabilir."
}

$python = $null
$pythonArgs = @()
foreach ($candidate in @(
    @{ Name = "py"; Args = @("-3") },
    @{ Name = "python"; Args = @() },
    @{ Name = "python3"; Args = @() }
)) {
    $command = Get-Command $candidate.Name -ErrorAction SilentlyContinue
    if (-not $command) { continue }
    try {
        & $command.Source @($candidate.Args) --version 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $python = $command
            $pythonArgs = $candidate.Args
            break
        }
    } catch {
        continue
    }
}
if (-not $python) {
    throw @"
Python 3 bulunamadi. Iki secenek var:
  1. winget install --id Python.Python.3.12 --scope user
  2. Seed'i Docker uzerinden kosturun (bkz. README, "Yerel demo"):
     docker run --rm --network backend_default -v "`$PWD:/app" -w /app ``
       python:3.12-slim python seed/seed_demo.py ``
       --base-url http://backend:8080 --skip-media-check
"@
}

Push-Location -LiteralPath $backendRoot
try {
    if ($ResetData) {
        Write-Host "Demo verileri ve volume'ler sifirlaniyor..."
        Invoke-Native "docker compose down" { docker compose down --volumes --remove-orphans }
    }

    Write-Host "Backend, PostgreSQL ve MinIO baslatiliyor..."
    Invoke-Native "docker compose up" { docker compose up --detach --build --wait }

    $healthy = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 5
            if ($health.status -eq "ok" -and $health.database -eq "up") {
                $healthy = $true
                break
            }
        } catch { }
        if ($attempt -lt 30) { Start-Sleep -Seconds 2 }
    }
    if (-not $healthy) {
        Invoke-Native "docker compose ps" { docker compose ps }
        throw "Backend 60 saniye icinde saglikli duruma gelmedi."
    }

    # Migration surumu, AI tablolarinin var olmasinin on kosulu.
    Write-Host "Migration surumu dogrulaniyor..."
    $version = (docker compose exec -T postgres psql -U nexi -d nexi -tAc `
        "SELECT MAX(version::int) FROM flyway_schema_history WHERE success" 2>$null) -join ""
    $version = $version.Trim()
    if ($version -ne "19") {
        throw "Beklenen migration surumu 19, bulunan: '$version'."
    }
    Write-Host "  Migration V1-V19 uygulandi."

    Write-Host "Kurgu demo verileri hazirlaniyor..."
    Invoke-Native "Demo seed islemi" { & $python.Source @pythonArgs $seedFile --base-url $BaseUrl }

    Write-Host "Demo hazir: $BaseUrl"
    Invoke-Native "docker compose ps" { docker compose ps }
} finally {
    Pop-Location
}
