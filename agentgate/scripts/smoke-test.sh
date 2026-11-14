#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "AgentGate M01: Multi-Container Environment Smoke Test (Bash)"
echo "=========================================================="

echo -e "\n[1/5] Checking Docker Compose Containers..."
docker compose ps

echo -e "\n[2/5] Checking Infrastructure Health..."
echo -n "  Checking PostgreSQL (port 5432)... "
nc -z 127.0.0.1 5432 && echo "OK" || (echo "FAILED"; exit 1)

echo -n "  Checking Redis (port 6379)... "
nc -z 127.0.0.1 6379 && echo "OK" || (echo "FAILED"; exit 1)

echo -n "  Checking Keycloak realm (http://localhost:8180/realms/agentgate)... "
curl -sf http://localhost:8180/realms/agentgate > /dev/null && echo "OK" || (echo "FAILED"; exit 1)

echo -e "\n[3/5] Checking Application Services..."
echo -n "  Checking Gateway Actuator Health (http://localhost:8080/actuator/health)... "
curl -sf http://localhost:8080/actuator/health > /dev/null && echo "OK" || (echo "FAILED"; exit 1)

echo -n "  Checking Demo Tools (http://localhost:8082/actuator/health)... "
curl -sf http://localhost:8082/actuator/health > /dev/null && echo "OK" || (echo "FAILED"; exit 1)

echo -e "\n[4/5] Running MCP Protocol & Security Verification Script..."
python3 scripts/mcp-client-spike.py

echo -e "\n[5/5] Docker Container Memory Footprint:"
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"

echo -e "\n=========================================================="
echo "ALL M01 MULTI-CONTAINER SMOKE TESTS PASSED SUCCESSFULLY!"
echo "=========================================================="

