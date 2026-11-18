# AgentGate Day 1 Verification Script (PowerShell wrapper)
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "AgentGate: Day 1 Acceptance Verification Suite" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Check Docker Compose status
Write-Host "`n[1/2] Verifying Docker Stack Status..." -ForegroundColor Yellow
cd agentgate
docker compose ps
if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker Compose stack is not running!"
    exit 1
}

# Run Python verification script
Write-Host "`n[2/2] Running Day 1 Scripted Verification..." -ForegroundColor Yellow
python scripts/verify-day1.py
if ($LASTEXITCODE -ne 0) {
    Write-Error "Day 1 Verification Failed!"
    exit 1
}

Write-Host "`nDay 1 Verification Suite Completed Successfully!" -ForegroundColor Green
