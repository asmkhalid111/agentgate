# AgentGate M01 Multi-Container Smoke Test (PowerShell)
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "AgentGate M01: Multi-Container Environment Smoke Test" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Container Status
Write-Host "`n[1/5] Checking Docker Compose Containers..." -ForegroundColor Yellow
docker compose ps
if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker Compose ps failed. Are containers started?"
    exit 1
}

# 2. Infrastructure Health Checks
Write-Host "`n[2/5] Checking Infrastructure Health..." -ForegroundColor Yellow

# Postgres
Write-Host "  Checking PostgreSQL (port 5432)..." -NoNewline
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("127.0.0.1", 5432)
    $tcp.Close()
    Write-Host " OK" -ForegroundColor Green
} catch {
    Write-Host " FAILED ($($_))" -ForegroundColor Red
    exit 1
}

# Redis
Write-Host "  Checking Redis (port 6379)..." -NoNewline
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("127.0.0.1", 6379)
    $tcp.Close()
    Write-Host " OK" -ForegroundColor Green
} catch {
    Write-Host " FAILED ($($_))" -ForegroundColor Red
    exit 1
}

# Keycloak
Write-Host "  Checking Keycloak realm (http://localhost:8180/realms/agentgate)..." -NoNewline
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8180/realms/agentgate" -Method Get -TimeoutSec 15
    if ($resp.realm -eq "agentgate") {
        Write-Host " OK (Realm: $($resp.realm))" -ForegroundColor Green
    } else {
        Write-Host " UNEXPECTED RESPONSE" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host " FAILED ($($_))" -ForegroundColor Red
    exit 1
}

# 3. Application Health Checks
Write-Host "`n[3/5] Checking Application Services..." -ForegroundColor Yellow

# Gateway
Write-Host "  Checking Gateway Actuator Health (http://localhost:8080/actuator/health)..." -NoNewline
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get -TimeoutSec 10
    Write-Host " OK (Status: $($resp.status))" -ForegroundColor Green
} catch {
    Write-Host " FAILED ($($_))" -ForegroundColor Red
    exit 1
}

# Demo Tools
Write-Host "  Checking Demo Tools (http://localhost:8082/actuator/health)..." -NoNewline
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8082/actuator/health" -Method Get -TimeoutSec 10
    Write-Host " OK (Status: $($resp.status))" -ForegroundColor Green
} catch {
    Write-Host " FAILED ($($_))" -ForegroundColor Red
    exit 1
}

# 4. MCP Security & Protocol Spike Test
Write-Host "`n[4/5] Running MCP Protocol & Security Verification Script..." -ForegroundColor Yellow
python scripts/mcp-client-spike.py
if ($LASTEXITCODE -ne 0) {
    Write-Error "MCP client spike verification failed!"
    exit 1
}

# 5. Memory Consumption Audit
Write-Host "`n[5/5] Docker Container Memory Footprint:" -ForegroundColor Yellow
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "ALL M01 MULTI-CONTAINER SMOKE TESTS PASSED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

