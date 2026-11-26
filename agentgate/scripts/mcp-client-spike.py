"""
AgentGate — Milestone M01 MCP Client Spike & Verification Script
Protocol: Streamable HTTP MCP (Spring AI 2.0.2 / Spring Boot 4.0.9)
Identity: Keycloak 26.x OpenID Connect Token Verification
"""

from local_config import credential

import sys
import json
import requests

KEYCLOAK_TOKEN_URL = "http://localhost:8180/realms/agentgate/protocol/openid-connect/token"
GATEWAY_MCP_URL = "http://localhost:8080/mcp"

CLIENTS = {
    "support": {
        "client_id": "agent-support-bot",
        "client_secret": credential("AGENT_SUPPORT_SECRET")
    },
    "finance": {
        "client_id": "agent-finance-bot",
        "client_secret": credential("AGENT_CLIENT_SECRET")
    },
    "unauthorized": {
        "client_id": "agent-unauthorized-bot",
        "client_secret": credential("AGENT_UNAUTH_SECRET")
    }
}


def get_token(client_id: str, client_secret: str) -> str:
    """Acquire JWT token via client_credentials grant from Keycloak."""
    payload = {
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret
    }
    resp = requests.post(KEYCLOAK_TOKEN_URL, data=payload, timeout=10)
    if resp.status_code != 200:
        raise RuntimeError(f"Failed to obtain token for {client_id}: {resp.status_code} {resp.text}")
    return resp.json()["access_token"]


def send_mcp_request(token: str, rpc_request: dict, session_id: str = None) -> tuple[int, dict, dict]:
    """Send JSON-RPC over Streamable HTTP POST to the Gateway MCP endpoint."""
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if session_id:
        headers["Mcp-Session-Id"] = session_id

    resp = requests.post(GATEWAY_MCP_URL, json=rpc_request, headers=headers, timeout=10)
    
    # Try parsing json or SSE stream
    body = None
    try:
        body = resp.json()
    except Exception:
        text = resp.text
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("data:"):
                try:
                    body = json.loads(line[5:].strip())
                    break
                except Exception:
                    pass
        if body is None:
            body = text

    return resp.status_code, dict(resp.headers), body


def run_spike():
    print("=" * 70)
    print("AgentGate M01: MCP Streamable HTTP & Security Verification Spike")
    print("=" * 70)

    # 1. Test Unauthenticated Access
    print("\n[Test 1] Unauthenticated MCP invocation (No Token)")
    status, headers, body = send_mcp_request(
        token=None,
        rpc_request={"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}
    )
    print(f"  HTTP Status: {status} (Expected: 401)")
    assert status == 401, f"Expected 401 Unauthorized, got {status}"
    print("  PASS: Unauthenticated request rejected with HTTP 401.")

    # 2. Acquire Tokens
    print("\n[Step] Acquiring Keycloak Tokens...")
    token_support = get_token(CLIENTS["support"]["client_id"], CLIENTS["support"]["client_secret"])
    print(f"  Acquired token for {CLIENTS['support']['client_id']} (len={len(token_support)})")
    token_finance = get_token(CLIENTS["finance"]["client_id"], CLIENTS["finance"]["client_secret"])
    print(f"  Acquired token for {CLIENTS['finance']['client_id']} (len={len(token_finance)})")
    token_unauth = get_token(CLIENTS["unauthorized"]["client_id"], CLIENTS["unauthorized"]["client_secret"])
    print(f"  Acquired token for {CLIENTS['unauthorized']['client_id']} (len={len(token_unauth)})")

    # 3. Test Unauthorized Agent (agent-unauthorized-bot)
    print("\n[Test 2] Unauthorized Agent Invocation (Missing agent:invoke scope)")
    status, headers, body = send_mcp_request(
        token=token_unauth,
        rpc_request={"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
    )
    print(f"  HTTP Status: {status} (Expected: 403)")
    assert status == 403, f"Expected 403 Forbidden, got {status}"
    print("  PASS: Unauthorized agent rejected with HTTP 403.")

    # 4. Initialize MCP Session with Authorized Agent (agent-support-bot)
    print("\n[Test 3] MCP Session Initialize (agent-support-bot)")
    init_payload = {
        "jsonrpc": "2.0",
        "id": 10,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "agentgate-spike-client", "version": "1.0.0"}
        }
    }
    status, headers, body = send_mcp_request(token=token_support, rpc_request=init_payload)
    print(f"  HTTP Status: {status}")
    print(f"  Response Body: {json.dumps(body, indent=2) if isinstance(body, dict) else body}")
    session_id = headers.get("mcp-session-id") or headers.get("Mcp-Session-Id")
    if session_id:
        print(f"  Negotiated Session ID: {session_id}")

    # 5. Tool Discovery (tools/list)
    print("\n[Test 4] Tool Discovery (agent-support-bot) - Filtered per FR1")
    tools_list_payload = {
        "jsonrpc": "2.0",
        "id": 11,
        "method": "tools/list",
        "params": {}
    }
    status, headers, body = send_mcp_request(token=token_support, rpc_request=tools_list_payload, session_id=session_id)
    print(f"  HTTP Status: {status}")
    print(f"  Discovered Tools: {json.dumps(body, indent=2) if isinstance(body, dict) else body}")
    assert status == 200, f"Expected 200 OK, got {status}"
    tools = []
    if isinstance(body, dict) and "result" in body and "tools" in body["result"]:
        tools = [t.get("name") for t in body["result"]["tools"]]
    print(f"  Discovered tool names for support bot: {tools}")
    assert "m01_status" in tools, f"m01_status not discovered! Found: {tools}"
    assert "m01_restricted_admin_tool" not in tools, f"m01_restricted_admin_tool should NOT be visible to support bot! Found: {tools}"
    print("  PASS: Permitted tool discovered; restricted admin tool correctly hidden from support agent.")

    # Check that finance agent does NOT discover m01_restricted_admin_tool under least privilege
    status, headers, body = send_mcp_request(token=token_finance, rpc_request=tools_list_payload, session_id=session_id)
    finance_tools = []
    if isinstance(body, dict) and "result" in body and "tools" in body["result"]:
        finance_tools = [t.get("name") for t in body["result"]["tools"]]
    print(f"  Discovered tool names for finance bot: {finance_tools}")
    assert "m01_restricted_admin_tool" not in finance_tools, f"m01_restricted_admin_tool should NOT be visible to finance bot (least privilege)! Found: {finance_tools}"
    print("  PASS: Restricted admin tool correctly hidden from finance agent under least privilege.")

    # 6. Permitted Tool Call: m01_status (agent-support-bot)
    print("\n[Test 5] Permitted Tool Call: m01_status (agent-support-bot)")
    call_status_payload = {
        "jsonrpc": "2.0",
        "id": 12,
        "method": "tools/call",
        "params": {
            "name": "m01_status",
            "arguments": {"message": "hello from support bot"}
        }
    }
    status, headers, body = send_mcp_request(token=token_support, rpc_request=call_status_payload, session_id=session_id)
    print(f"  HTTP Status: {status}")
    print(f"  Tool Output: {json.dumps(body, indent=2) if isinstance(body, dict) else body}")
    assert status == 200, f"Expected 200 OK, got {status}"
    assert isinstance(body, dict) and "result" in body and not body["result"].get("isError"), f"Failed execution: {body}"
    print("  PASS: Permitted tool m01_status successfully executed.")

    # 7. Restricted Tool Call WITHOUT Authority: m01_restricted_admin_tool (agent-support-bot)
    print("\n[Test 6] Direct Restricted Tool Call WITHOUT Authority (agent-support-bot)")
    call_restricted_payload = {
        "jsonrpc": "2.0",
        "id": 13,
        "method": "tools/call",
        "params": {
            "name": "m01_restricted_admin_tool",
            "arguments": {"action": "unauthorized_reset"}
        }
    }
    status, headers, body = send_mcp_request(token=token_support, rpc_request=call_restricted_payload, session_id=session_id)
    print(f"  HTTP Status: {status}")
    print(f"  Response: {json.dumps(body, indent=2) if isinstance(body, dict) else body}")
    # Verify that the restricted call failed
    is_rejected = False
    if status in (401, 403, 500):
        is_rejected = True
    elif isinstance(body, dict):
        if "error" in body:
            is_rejected = True
        elif "result" in body and body["result"].get("isError"):
            is_rejected = True
        elif "result" in body and any("Access Denied" in str(c) for c in body["result"].get("content", [])):
            is_rejected = True
    assert is_rejected, f"Restricted tool invocation should have failed for unprivileged agent! Body: {body}"
    print("  PASS: Restricted tool invocation rejected for unprivileged agent.")

    # 8. Restricted Tool Call WITHOUT Authority: m01_restricted_admin_tool (agent-finance-bot)
    print("\n[Test 7] Direct Restricted Tool Call for Finance Bot (Least Privilege Rejection)")
    status, headers, body = send_mcp_request(token=token_finance, rpc_request=call_restricted_payload, session_id=session_id)
    print(f"  HTTP Status: {status}")
    print(f"  Response: {json.dumps(body, indent=2) if isinstance(body, dict) else body}")
    is_rejected = False
    if status in (401, 403, 500):
        is_rejected = True
    elif isinstance(body, dict):
        if "error" in body:
            is_rejected = True
        elif "result" in body and body["result"].get("isError"):
            is_rejected = True
        elif "result" in body and any("Access Denied" in str(c) for c in body["result"].get("content", [])):
            is_rejected = True
    assert is_rejected, f"Restricted tool invocation should have failed for non-admin finance agent! Body: {body}"
    print("  PASS: Restricted tool invocation rejected for non-admin finance agent under least privilege.")

    print("\n" + "=" * 70)
    print("ALL 7 MCP STREAMABLE HTTP & SECURITY SPIKE TESTS PASSED!")
    print("=" * 70)


if __name__ == "__main__":
    try:
        run_spike()
    except Exception as e:
        print(f"\n[FAIL] SPIKE FAILED: {e}", file=sys.stderr)
        sys.exit(1)
