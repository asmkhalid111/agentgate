import urllib.request
import urllib.parse
import json
import subprocess
import time

KEYCLOAK_URL = "http://localhost:8180/realms/agentgate/protocol/openid-connect/token"
GATEWAY_URL = "http://localhost:8080/mcp"

def get_token(client_id, client_secret):
    data = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret
    }).encode("utf-8")
    req = urllib.request.Request(KEYCLOAK_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req) as resp:
        body = json.loads(resp.read().decode("utf-8"))
        return body["access_token"]

def parse_sse(raw):
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip())
    if raw.strip().startswith("{"):
        return json.loads(raw.strip())
    return None

def mcp_call(token, session_id, tool_name, args, req_id=10):
    call_payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": args
        }
    }
    req = urllib.request.Request(GATEWAY_URL, data=json.dumps(call_payload).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)

    with urllib.request.urlopen(req) as resp:
        raw = resp.read().decode("utf-8")
        return parse_sse(raw) or {}

def main():
    print("=== AgentGate Live FR6 Rate Limiting Verification ===")
    
    print("\n1. Acquiring token for agent-finance-bot...")
    token = get_token("agent-finance-bot", "finance_dev_secret_123")
    print("   Token acquired.")

    print("\n2. Initializing MCP session...")
    init_payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "live-verifier", "version": "1.0.0"}
        }
    }
    req = urllib.request.Request(GATEWAY_URL, data=json.dumps(init_payload).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")

    with urllib.request.urlopen(req) as resp:
        session_id = resp.headers.get("Mcp-Session-Id")
        print(f"   Session established: {session_id}")

    # Determine current minute bucket
    minute_bucket = int(time.time() * 1000) // 60000
    agent_key = f"agentgate:ratelimit:agent:agent-finance-bot:{minute_bucket}"
    tool_key = f"agentgate:ratelimit:tool:orders_get:{minute_bucket}"

    # Reset counters for this minute bucket
    subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "del", agent_key, tool_key], check=True, capture_output=True)
    print(f"   Cleaned up test keys for bucket {minute_bucket}")

    print("\n3. Testing unmetered built-in tool: get_approval_result...")
    result = mcp_call(token, session_id, "get_approval_result", {"approvalId": "appr-nonexistent"}, req_id=101)
    print(f"   get_approval_result response: {result.get('result', {}).get('content', [{}])[0].get('text')}")
    
    # Verify no Redis keys created for unmetered tool
    keys_out = subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "keys", f"agentgate:ratelimit:*:*{minute_bucket}"], capture_output=True, text=True).stdout
    print(f"   Redis keys after get_approval_result: {keys_out.strip() or '(none)'}")
    assert not keys_out.strip(), "Unmetered tool must not create rate limit keys"
    print("   -> PASS: Built-in metadata tool is not metered.")

    print("\n4. Testing metered governed tool within limit: orders_get...")
    result1 = mcp_call(token, session_id, "orders_get", {"orderId": "ord-1"}, req_id=102)
    print(f"   Call 1 response: {result1.get('result', {}).get('content', [{}])[0].get('text')}")
    
    agent_val = subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "get", agent_key], capture_output=True, text=True).stdout.strip()
    tool_val = subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "get", tool_key], capture_output=True, text=True).stdout.strip()
    print(f"   Counters after 1 call: agent={agent_val}, tool={tool_val}")
    assert agent_val == "1" and tool_val == "1", f"Expected counters 1, got agent={agent_val}, tool={tool_val}"
    print("   -> PASS: First governed call admitted and incremented counters.")

    print("\n5. Testing rate limit exhaustion: Setting agent counter to limit (60)...")
    subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "set", agent_key, "60"], check=True)
    print("   Counter set to 60.")

    print("\n6. Calling governed tool when limit is reached...")
    blocked_result = mcp_call(token, session_id, "orders_get", {"orderId": "ord-2"}, req_id=103)
    error_text = blocked_result.get("result", {}).get("content", [{}])[0].get("text", "")
    is_error = blocked_result.get("result", {}).get("isError", False)
    print(f"   Blocked call isError: {is_error}")
    print(f"   Blocked call message: {error_text}")
    assert is_error is True, "Result must have isError: true"
    assert "RATE_LIMIT_EXCEEDED" in error_text, f"Unexpected error text: {error_text}"
    print("   -> PASS: Exceeded call rejected with RATE_LIMIT_EXCEEDED error.")

    print("\n7. Verifying counter was NOT incremented on rejection...")
    agent_val_after = subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "get", agent_key], capture_output=True, text=True).stdout.strip()
    print(f"   Agent counter after rejected call: {agent_val_after}")
    assert agent_val_after == "60", f"Counter should remain 60, but was {agent_val_after}"
    print("   -> PASS: Atomic check did not increment counter when limit was exceeded.")

    print("\n8. Verifying built-in unmetered tools still work when rate limited...")
    unmetered_result = mcp_call(token, session_id, "get_approval_result", {"approvalId": "appr-nonexistent"}, req_id=104)
    unmetered_text = unmetered_result.get("result", {}).get("content", [{}])[0].get("text", "")
    print(f"   get_approval_result text: {unmetered_text}")
    assert "RATE_LIMIT_EXCEEDED" not in unmetered_text, "Unmetered tool must not be blocked by rate limit"
    print("   -> PASS: get_approval_result remains accessible even when agent rate limit is exceeded.")

    # Clean up test keys
    subprocess.run(["docker", "exec", "agentgate-redis", "redis-cli", "del", agent_key, tool_key], check=True, capture_output=True)
    print("\n=== ALL LIVE VERIFICATION CHECKS PASSED ===")

if __name__ == "__main__":
    main()
