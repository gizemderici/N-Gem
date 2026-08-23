[CmdletBinding()]
param(
    [switch]$ResetData,
    [switch]$ConfirmReset,
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$backendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $backendRoot "docker-compose.yml"
$seedFile = Join-Path $backendRoot "seed\seed_demo.py"

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "Beklenen docker-compose.yml bulunamadı: $composeFile"
}
if (-not (Test-Path -LiteralPath $seedFile -PathType Leaf)) {
    throw "Beklenen demo seed betiği bulunamadı: $seedFile"
}
if ($ResetData -and -not $ConfirmReset) {
    throw "Reset PostgreSQL ve MinIO demo volume'lerini siler. Onay için -ResetData -ConfirmReset kullanın."
}
if ($ConfirmReset -and -not $ResetData) {
    throw "-ConfirmReset yalnızca -ResetData ile birlikte kullanılabilir."
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
    throw "Python 3 bulunamadı. Python 3 kurup yeniden deneyin."
}

Push-Location -LiteralPath $backendRoot
try {
    if ($ResetData) {
        Write-Host "Demo verileri ve volume'ler sıfırlanıyor..."
        & docker compose down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { throw "docker compose down başarısız oldu." }
    }

    Write-Host "Backend, PostgreSQL ve MinIO başlatılıyor..."
    & docker compose up --detach --build --wait
    if ($LASTEXITCODE -ne 0) { throw "docker compose up başarısız oldu." }

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
        & docker compose ps
        throw "Backend 60 saniye içinde sağlıklı duruma gelmedi."
    }

    Write-Host "Kurgu demo verileri hazırlanıyor..."
    & $python.Source @pythonArgs $seedFile --base-url $BaseUrl
    if ($LASTEXITCODE -ne 0) { throw "Demo seed işlemi başarısız oldu." }

    Write-Host "Demo hazır: $BaseUrl"
    & docker compose ps
} finally {
    Pop-Location
}
