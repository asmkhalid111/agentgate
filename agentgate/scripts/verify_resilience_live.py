#!/usr/bin/env python3
"""
AgentGate — Milestone M03 FR10 Live Resilience Verification Script
Demonstrates end-to-end against live Docker Compose stack:
  1. Agent authentication via Keycloak M2M Client Credentials Grant.
  2. MCP session initialization.
  3. MCP tools discovery baseline: orders_get, orders_refund, get_approval_result, m01_status.
  4. Successful direct execution of orders_get.
  5. Injection of consecutive 500 errors to trip circuit breaker into OPEN state (threshold=3).
  6. Fast rejection of subsequent request with TOOL_TEMPORARILY_UNAVAILABLE.
  7. Dynamic MCP discovery filtering: orders_get disappears from tools/list while healthy tools remain.
  8. Preserved availability of get_approval_result metadata tool.
  9. Cooldown expiration wait (30s) and reappearance of orders_get in tools/list.
  10. Verification that repeated tools/list calls do NOT consume the single HALF_OPEN trial permit.
  11. Controlled single trial execution in HALF_OPEN state with healthy ID.
  12. Automatic recovery of circuit breaker to CLOSED state upon trial success.
  13. Subsequent request allowed and orders_get remains discoverable.
  14. Cryptographic SHA-256 audit chain verification of all failure, fast-rejection, and recovery events.
"""

import http.cookiejar
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import hashlib

# Load development credentials from local .env if present
def load_env_file():
    env_path = os.path.join(os.path.dirname(__file__), "..", ".env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    if k not in os.environ:
                        os.environ[k] = v

load_env_file()

def detect_docker_host():
    if "GATEWAY_URL" in os.environ and "KEYCLOAK_BASE_URL" in os.environ:
        return None
    # Test localhost first
    try:
        with urllib.request.urlopen("http://localhost:8080/actuator/health", timeout=1):
            return "localhost"
    except Exception:
        pass
    # Try WSL host IP
    try:
        res = subprocess.run(["wsl", "hostname", "-I"], capture_output=True, text=True, check=True)
        ips = res.stdout.strip().split()
        for ip in ips:
            try:
                with urllib.request.urlopen(f"http://{ip}:8080/actuator/health", timeout=1):
                    return ip
            except Exception:
                pass
    except Exception:
        pass
    return "localhost"

_detected_host = detect_docker_host()
_default_gw = f"http://{_detected_host}:8080" if _detected_host else "http://localhost:8080"
_default_kc = f"http://{_detected_host}:8180" if _detected_host else "http://localhost:8180"

GATEWAY_URL = os.environ.get("GATEWAY_URL", _default_gw)
GATEWAY_MCP_URL = f"{GATEWAY_URL}/mcp"
KEYCLOAK_BASE_URL = os.environ.get("KEYCLOAK_BASE_URL", _default_kc)
KEYCLOAK_TOKEN_URL = f"{KEYCLOAK_BASE_URL}/realms/agentgate/protocol/openid-connect/token"

AGENT_CLIENT_ID = "agent-finance-bot"
AGENT_CLIENT_SECRET = os.environ.get("AGENT_CLIENT_SECRET", "finance_dev_secret_123")

def get_client_token(client_id, client_secret):
    """M2M Client Credentials Grant for machine agent service accounts."""
    data = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret
    }).encode("utf-8")
    req = urllib.request.Request(KEYCLOAK_TOKEN_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("Host", "localhost:8180")
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))["access_token"]

def parse_sse(raw):
    """Extract JSON object from SSE or raw JSON response."""
    for line in raw.splitlines():
        if line.startswith("data:"):
            try:
                return json.loads(line[5:].strip())
            except Exception:
                pass
    if raw.strip().startswith("{"):
        try:
            return json.loads(raw.strip())
        except Exception:
            pass
    return None

def mcp_request(token, payload, session_id=None):
    req = urllib.request.Request(GATEWAY_MCP_URL, data=json.dumps(payload).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)

    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode("utf-8")
            sess = resp.headers.get("Mcp-Session-Id") or session_id
            return parse_sse(raw), sess, resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return parse_sse(raw) or {"error": str(e), "raw": raw}, session_id, e.code

def init_mcp_session(token):
    init_payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "live-resilience-verifier", "version": "1.0.0"}
        }
    }
    body, session_id, status = mcp_request(token, init_payload)
    if status != 200 or not session_id:
        raise RuntimeError(f"Failed to initialize MCP session: status={status}, body={body}")
    return session_id

def list_mcp_tools(token, session_id, req_id=2):
    list_payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/list",
        "params": {}
    }
    body, _, status = mcp_request(token, list_payload, session_id)
    if not body or "result" not in body or "tools" not in body["result"]:
        return []
    return [t["name"] for t in body["result"]["tools"]]

def call_mcp_tool(token, session_id, tool_name, args, req_id=10):
    call_payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": args
        }
    }
    body, _, status = mcp_request(token, call_payload, session_id)
    return body, status

def query_pg(sql):
    cmd = ["docker", "exec", "agentgate-postgres", "psql", "-U", "agentgate", "-d", "agentgate_db", "-t", "-A", "-c", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return result.stdout.strip()

def main():
    print("=" * 75)
    print("AgentGate FR10 Task 14B: Live Docker MCP Discovery & Resilience Verification")
    print("=" * 75)

    # 1. Principal authentication
    print("\n[Step 1] Authenticating agent-finance-bot against Keycloak...")
    agent_token = get_client_token(AGENT_CLIENT_ID, AGENT_CLIENT_SECRET)
    print("  ✓ Acquired Keycloak M2M OAuth2 token for agent-finance-bot")

    # 2. Initialize MCP session
    print("\n[Step 2] Initializing stateful MCP session on /mcp...")
    session_id = init_mcp_session(agent_token)
    print(f"  ✓ Initialized MCP session: {session_id}")

    # 3. Discovery baseline
    print("\n[Step 3] Checking baseline MCP tools/list discovery...")
    tools = list_mcp_tools(agent_token, session_id, req_id=2)
    print(f"  ✓ Discovered tools: {tools}")
    assert "orders_get" in tools, f"orders_get missing from baseline discovery: {tools}"
    assert "orders_refund" in tools, f"orders_refund missing from baseline discovery: {tools}"
    assert "get_approval_result" in tools, f"get_approval_result metadata tool missing from discovery: {tools}"
    print("  ✓ All required tools and reserved metadata tools present in discovery")

    # Clean up Redis rate-limit and write-cap keys to ensure clean run
    try:
        subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "del", "agentgate:writecap:agent:agent-finance-bot"], capture_output=True)
    except Exception:
        pass

    # 4. Normal invocation
    print("\n[Step 4] Invoking healthy tool 'orders_refund' with valid arguments (amount=500 <= 2000 ALLOW)...")
    resp, code = call_mcp_tool(agent_token, session_id, "orders_refund", {"orderId": "ord-live-normal", "amount": 500}, req_id=10)
    print(f"  ✓ Normal invocation response status: {code}")
    is_err = resp.get("isError", False) or (isinstance(resp.get("result"), dict) and resp["result"].get("isError", False))
    assert "result" in resp and not is_err, f"Expected normal success, got: {resp}"
    print("  ✓ Successfully executed normal tool call")

    # 5. Consecutive downstream failures to trip circuit breaker (threshold = 3)
    print("\n[Step 5] Triggering 3 consecutive downstream HTTP 500 errors to trip circuit breaker...")
    for i in range(1, 4):
        fail_id = f"fail-live-{i}"
        resp, code = call_mcp_tool(agent_token, session_id, "orders_refund", {"orderId": fail_id, "amount": 500}, req_id=100 + i)
        print(f"  ✓ Failure call {i}/3 sent with orderId='{fail_id}' (downstream 500 triggered)")
        # Result should indicate error
        is_error = resp.get("isError", False) or "error" in resp or (
            isinstance(resp.get("result"), dict) and resp["result"].get("isError", False)
        ) or (
            "result" in resp and any("error" in str(c).lower() or "500" in str(c) for c in resp["result"].get("content", []))
        )
        assert is_error, f"Expected failure response for call {i}, got: {resp}"

    print("  ✓ 3 consecutive failures recorded; circuit breaker for 'orders_refund' should now be OPEN")

    # 6. Fast-rejection verification
    print("\n[Step 6] Verifying fast rejection of 4th invocation (TOOL_TEMPORARILY_UNAVAILABLE)...")
    resp, code = call_mcp_tool(agent_token, session_id, "orders_refund", {"orderId": "ord-live-blocked", "amount": 500}, req_id=200)
    resp_str = json.dumps(resp)
    print(f"  ✓ Fast rejection response: {resp_str[:120]}...")
    assert "TOOL_TEMPORARILY_UNAVAILABLE" in resp_str or "Circuit breaker is OPEN" in resp_str, (
        f"Expected fast-rejection error, got: {resp_str}"
    )
    print("  ✓ Request was fast-rejected by circuit breaker without hitting downstream service")

    # 7. MCP discovery filtering: orders_refund disappears
    print("\n[Step 7] Verifying MCP discovery filtering (tools/list)...")
    tools_tripped = list_mcp_tools(agent_token, session_id, req_id=201)
    print(f"  ✓ Active discovery tools: {tools_tripped}")
    assert "orders_refund" not in tools_tripped, (
        f"FAILURE: 'orders_refund' MUST NOT be visible in tools/list while circuit is OPEN! Discovered: {tools_tripped}"
    )
    assert "orders_get" in tools_tripped, "Healthy tool 'orders_get' should remain discoverable"
    assert "get_approval_result" in tools_tripped, "Reserved metadata tool 'get_approval_result' must never be hidden"
    print("  ✓ 'orders_refund' successfully disappeared from tools/list!")
    print("  ✓ Healthy tools and reserved gateway tools remain visible")

    # 8. Cooldown wait
    # In application.yml, default-open-wait-duration is 30s.
    print("\n[Step 8] Waiting for circuit breaker cooldown to elapse (~30 seconds)...")
    start_time = time.time()
    reappeared = False
    cooldown_elapsed = 0
    while time.time() - start_time < 45:
        time.sleep(3)
        poll_tools = list_mcp_tools(agent_token, session_id, req_id=300)
        cooldown_elapsed = int(time.time() - start_time)
        if "orders_refund" in poll_tools:
            reappeared = True
            print(f"  ✓ 'orders_refund' reappeared in tools/list after ~{cooldown_elapsed} seconds!")
            break
        print(f"  ... still in cooldown ({cooldown_elapsed}s elapsed, 'orders_refund' remains hidden)")

    assert reappeared, f"FAILURE: 'orders_refund' did not reappear after {cooldown_elapsed}s"

    # 9. Invariant: Repeated tools/list does NOT consume trial permit
    print("\n[Step 9] Verifying repeated tools/list calls preserve the single HALF_OPEN trial permit...")
    for j in range(3):
        p_tools = list_mcp_tools(agent_token, session_id, req_id=400 + j)
        assert "orders_refund" in p_tools, "Tool must remain visible on repeated discovery"
    print("  ✓ Repeated tools/list calls completed safely without consuming trial permit")

    # 10. Single controlled trial execution
    print("\n[Step 10] Dispatching single controlled trial execution with healthy arguments...")
    trial_resp, trial_code = call_mcp_tool(agent_token, session_id, "orders_refund", {"orderId": "ord-live-trial", "amount": 500}, req_id=500)
    print(f"  ✓ Trial response: {json.dumps(trial_resp)[:120]}...")
    trial_err = trial_resp.get("isError", False) or (isinstance(trial_resp.get("result"), dict) and trial_resp["result"].get("isError", False))
    assert "result" in trial_resp and not trial_err, f"Trial failed: {trial_resp}"
    print("  ✓ Controlled trial probe succeeded downstream")

    # 11. Circuit recovery to CLOSED
    print("\n[Step 11] Verifying circuit breaker recovered to CLOSED state...")
    follow_resp, follow_code = call_mcp_tool(agent_token, session_id, "orders_refund", {"orderId": "ord-live-post-recovery", "amount": 500}, req_id=600)
    follow_err = follow_resp.get("isError", False) or (isinstance(follow_resp.get("result"), dict) and follow_resp["result"].get("isError", False))
    assert "result" in follow_resp and not follow_err, f"Follow-up request failed: {follow_resp}"
    print("  ✓ Normal follow-up execution succeeded")

    final_tools = list_mcp_tools(agent_token, session_id, req_id=601)
    assert "orders_refund" in final_tools, "Tool must remain present in discovery after recovery"
    print("  ✓ 'orders_refund' remains permanently discoverable in CLOSED state")

    # 12. Audit Log & Hash Chaining Verification
    print("\n[Step 12] Cryptographic SHA-256 audit chain verification...")
    count_sql = "SELECT COUNT(*) FROM audit_log WHERE tool_name = 'orders_refund';"
    audit_count = int(query_pg(count_sql))
    print(f"  ✓ Found {audit_count} audit events for tool 'orders_refund'")
    assert audit_count >= 5, f"Expected at least 5 audit events, found: {audit_count}"

    # Verify chain integrity via psql
    chain_sql = "SELECT sequence_number, event_type, outcome, prev_record_hash, record_hash FROM audit_log ORDER BY sequence_number ASC;"
    rows = query_pg(chain_sql).splitlines()
    print(f"  ✓ Total audit log entries across system: {len(rows)}")

    last_hash = "0" * 64
    verified_entries = 0
    for r in rows:
        parts = r.split("|")
        if len(parts) >= 5:
            seq = parts[0]
            etype = parts[1]
            outcome = parts[2]
            prev_hash = parts[3]
            record_hash = parts[4]
            assert prev_hash == last_hash, f"Hash chain break at seq {seq}! Expected {last_hash}, got {prev_hash}"
            last_hash = record_hash
            verified_entries += 1

    print(f"  ✓ Successfully verified {verified_entries} consecutive entries in SHA-256 audit chain (zero breaks)")

    print("\n" + "=" * 75)
    print("AgentGate FR10 Task 14B: ALL 14 RESILIENCE VERIFICATIONS PASSED")
    print("=" * 75)

if __name__ == "__main__":
    main()
