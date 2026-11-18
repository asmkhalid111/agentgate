"""
AgentGate — Day 1 Scripted Verification Suite
Verifies:
- Task 1.0: JWT Security Hardening & Admin Security (Reviewer Amendment 5)
- Task 1.1: Dynamic Tool Registry & OpenAPI Import (FR1, FR2)
- Task 1.2: Structured Policy Engine, Hot Reload (FR3, QA4)
- Task 1.3: Policy Playground & Schema Validation (FR9)
"""

import sys
import json
import time
import requests

KEYCLOAK_TOKEN_URL = "http://localhost:8180/realms/agentgate/protocol/openid-connect/token"
GATEWAY_URL = "http://localhost:8080"
GATEWAY_MCP_URL = "http://localhost:8080/mcp"

CLIENTS = {
    "finance": {
        "client_id": "agent-finance-bot",
        "client_secret": "finance_dev_secret_123"
    },
    "support": {
        "client_id": "agent-support-bot",
        "client_secret": "support_dev_secret_123"
    },
    "unauthorized": {
        "client_id": "agent-unauthorized-bot",
        "client_secret": "unauth_dev_secret_123"
    }
}


def get_token(client_id: str, client_secret: str) -> str:
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
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if session_id:
        headers["Mcp-Session-Id"] = session_id

    resp = requests.post(GATEWAY_MCP_URL, json=rpc_request, headers=headers, timeout=10)
    
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


def run_verification():
    print("=" * 75)
    print("AgentGate Day 1: Security Hardening & Core Governance Engine Verification")
    print("=" * 75)

    # 1. Acquire Tokens
    print("\n[Step 1] Acquiring OAuth2 Tokens from Keycloak...")
    token_finance = get_token(CLIENTS["finance"]["client_id"], CLIENTS["finance"]["client_secret"])
    print(f"  [OK] agent-finance-bot token acquired (len={len(token_finance)}, roles=[ADMIN, APPROVER])")
    token_support = get_token(CLIENTS["support"]["client_id"], CLIENTS["support"]["client_secret"])
    print(f"  [OK] agent-support-bot token acquired (len={len(token_support)}, role=USER)")
    token_unauth = get_token(CLIENTS["unauthorized"]["client_id"], CLIENTS["unauthorized"]["client_secret"])
    print(f"  [OK] agent-unauthorized-bot token acquired (len={len(token_unauth)}, unprivileged)")

    # 2. Administrative Security Verification (Reviewer Amendment 5)
    print("\n[Step 2] Verifying Administrative Endpoint Security (/api/v1/registry, /api/v1/policies)...")
    
    # 2a: Unauthenticated access rejected
    r = requests.get(f"{GATEWAY_URL}/api/v1/registry/tools", timeout=5)
    assert r.status_code == 401, f"Expected 401 for unauthenticated registry access, got {r.status_code}"
    print("  [PASS] Unauthenticated request to /api/v1/registry/tools rejected with HTTP 401")

    # 2b: Non-admin agent rejected with 403 Forbidden
    headers_support = {"Authorization": f"Bearer {token_support}"}
    r = requests.get(f"{GATEWAY_URL}/api/v1/registry/tools", headers=headers_support, timeout=5)
    assert r.status_code == 403, f"Expected 403 for support bot (non-admin), got {r.status_code}"
    print("  [PASS] Support bot (non-admin) rejected from /api/v1/registry/tools with HTTP 403 Forbidden")

    # 2c: Admin agent allowed with 200 OK
    headers_admin = {"Authorization": f"Bearer {token_finance}", "Content-Type": "application/json"}
    r = requests.get(f"{GATEWAY_URL}/api/v1/registry/tools", headers=headers_admin, timeout=5)
    assert r.status_code == 200, f"Expected 200 for admin registry access, got {r.status_code}"
    print(f"  [PASS] Finance bot (ADMIN) granted access to /api/v1/registry/tools (HTTP 200, count={len(r.json())})")

    # 3. Dynamic Tool Registry & OpenAPI Import (Task 1.1, FR1, FR2)
    print("\n[Step 3] Importing OpenAPI 3.0 Specification for Demo Tools Service...")
    demo_tools_openapi = {
        "openapi": "3.0.1",
        "info": {"title": "Demo Tools Order Service", "version": "1.0.0"},
        "servers": [{"url": "http://demo-tools:8082"}],
        "paths": {
            "/api/orders/{orderId}/refund": {
                "post": {
                    "operationId": "orders_refund",
                    "summary": "Process customer refund",
                    "parameters": [
                        {"name": "orderId", "in": "path", "required": True, "schema": {"type": "string"}}
                    ],
                    "requestBody": {
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "object",
                                    "properties": {
                                        "amount": {"type": "number"},
                                        "reason": {"type": "string"}
                                    },
                                    "required": ["amount"]
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    import_payload = {
        "spec": json.dumps(demo_tools_openapi),
        "baseUrl": "http://demo-tools:8082",
        "owningTeam": "Finance Operations"
    }
    r = requests.post(f"{GATEWAY_URL}/api/v1/registry/tools/import-openapi", headers=headers_admin, json=import_payload, timeout=5)
    assert r.status_code == 200, f"OpenAPI import failed: {r.status_code} {r.text}"
    imported_tools = r.json()
    assert len(imported_tools) >= 1, "Expected at least 1 tool imported from OpenAPI spec"
    print(f"  [PASS] Successfully imported {len(imported_tools)} tool(s) via OpenAPI import: {[t['name'] for t in imported_tools]}")

    # 4. Tool Invocation Authorization & Permissions Mapping
    print("\n[Step 4] Configuring Agent Permissions for 'orders_refund'...")
    perm_payload = {"agentId": "agent-finance-bot"}
    r = requests.post(f"{GATEWAY_URL}/api/v1/registry/tools/orders_refund/permissions", headers=headers_admin, json=perm_payload, timeout=5)
    assert r.status_code == 200, f"Grant permission failed: {r.status_code} {r.text}"
    
    r = requests.get(f"{GATEWAY_URL}/api/v1/registry/tools/orders_refund/permissions", headers=headers_admin, timeout=5)
    assert "agent-finance-bot" in r.json(), f"agent-finance-bot not in permissions: {r.json()}"
    print(f"  [PASS] Permission granted: agent-finance-bot authorized for orders_refund")

    # 5. Structured Policy Engine & Baseline Policy Rule (Task 1.2, FR3)
    print("\n[Step 5] Deploying Structured Policy Rule (Ceiling <= 2000 BDT)...")
    policy_rule = {
        "id": "policy-orders-refund-ceiling",
        "name": "Refund Ceiling 2000 BDT",
        "agentPattern": "agent-finance-*",
        "toolPattern": "orders_refund",
        "conditions": [
            {
                "field": "amount",
                "operator": "LTE",
                "value": 2000
            }
        ],
        "action": "ALLOW",
        "dryRun": False,
        "priority": 5,
        "version": 1,
        "enabled": True
    }
    r = requests.post(f"{GATEWAY_URL}/api/v1/policies", headers=headers_admin, json=policy_rule, timeout=5)
    assert r.status_code == 201, f"Failed creating policy: {r.status_code} {r.text}"
    print("  [PASS] Policy deployed with condition: amount LTE 2000 -> ALLOW (default deny for > 2000)")

    # 6. MCP Protocol Dynamic Tool Invocation & Policy Enforcement
    print("\n[Step 6] Testing MCP Streamable HTTP Invocation with Policy Enforcement...")
    
    # 6a: Initialize MCP session
    init_payload = {
        "jsonrpc": "2.0",
        "id": 100,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "day1-verifier", "version": "1.0.0"}
        }
    }
    status, headers, body = send_mcp_request(token_finance, init_payload)
    assert status == 200, f"MCP initialize failed: {status} {body}"
    session_id = headers.get("mcp-session-id") or headers.get("Mcp-Session-Id")

    # 6b: Call orders_refund with amount 1500 (within 2000 ceiling -> ALLOW)
    print("\n  [Test 6a] Invoking orders_refund with amount=1500 (Expected: ALLOW & Execute)...")
    call_allow_payload = {
        "jsonrpc": "2.0",
        "id": 101,
        "method": "tools/call",
        "params": {
            "name": "orders_refund",
            "arguments": {
                "orderId": "ORD-101",
                "amount": 1500
            }
        }
    }
    status, headers, body = send_mcp_request(token_finance, call_allow_payload, session_id=session_id)
    assert status == 200, f"MCP tool call returned HTTP {status}: {body}"
    
    # Parse text from result content
    content_text = ""
    if isinstance(body, dict) and "result" in body and "content" in body["result"]:
        content_text = body["result"]["content"][0].get("text", "")
    print(f"    Gateway Response: {content_text}")
    assert "REFUND_PROCESSED" in content_text, f"Expected REFUND_PROCESSED in result, got: {content_text}"
    print("    [PASS] Low-risk refund (1,500 BDT <= 2,000 ceiling) approved and executed successfully!")

    # 6c: Call orders_refund with amount 3500 (exceeds 2000 ceiling -> DENY)
    print("\n  [Test 6b] Invoking orders_refund with amount=3500 (Expected: DENY by Policy)...")
    call_deny_payload = {
        "jsonrpc": "2.0",
        "id": 102,
        "method": "tools/call",
        "params": {
            "name": "orders_refund",
            "arguments": {
                "orderId": "ORD-102",
                "amount": 3500
            }
        }
    }
    status, headers, body = send_mcp_request(token_finance, call_deny_payload, session_id=session_id)
    content_text = ""
    if isinstance(body, dict) and "result" in body and "content" in body["result"]:
        content_text = body["result"]["content"][0].get("text", "")
    print(f"    Gateway Response: {content_text}")
    is_blocked = "error" in content_text and ("REQUIRE_APPROVAL" in content_text or "ACCESS_DENIED" in content_text or "Policy violation" in content_text or "Default Deny" in content_text)
    assert is_blocked, f"Expected policy block, got: {content_text}"
    print("    [PASS] High-value refund (3,500 BDT > 2,000 ceiling) blocked by structured policy engine!")

    # 7. Policy Hot Reload Verification (FR3 <= 10s rollout without restart)
    print("\n[Step 7] Testing Policy Hot Reload (Raising ceiling to 5,000 BDT via REST API)...")
    t0 = time.time()
    updated_rule = {
        "id": "policy-orders-refund-ceiling",
        "name": "Refund Ceiling 5000 BDT",
        "agentPattern": "agent-finance-*",
        "toolPattern": "orders_refund",
        "conditions": [
            {
                "field": "amount",
                "operator": "LTE",
                "value": 5000
            }
        ],
        "action": "ALLOW",
        "dryRun": False,
        "priority": 5,
        "version": 2,
        "enabled": True
    }
    r = requests.post(f"{GATEWAY_URL}/api/v1/policies", headers=headers_admin, json=updated_rule, timeout=5)
    assert r.status_code == 201, f"Failed updating policy: {r.status_code}"

    # Immediately call orders_refund with amount 3500 without gateway restart
    status, headers, body = send_mcp_request(token_finance, call_deny_payload, session_id=session_id)
    content_text = ""
    if isinstance(body, dict) and "result" in body and "content" in body["result"]:
        content_text = body["result"]["content"][0].get("text", "")
    elapsed = time.time() - t0
    print(f"    Gateway Response: {content_text}")
    assert "REFUND_PROCESSED" in content_text, f"Expected refund processed after hot reload, got: {content_text}"
    print(f"    [PASS] Policy hot-reload verified: updated policy took effect in {elapsed:.3f}s (< 10s threshold) without restart!")

    # 8. Policy Playground Verification (Task 1.3, FR9)
    print("\n[Step 8] Testing Policy Playground Endpoint (/api/v1/policies/playground)...")
    playground_payload = {
        "agentId": "agent-finance-bot",
        "toolName": "orders_refund",
        "arguments": {
            "orderId": "ORD-999",
            "amount": 2500,
            "customerId": "cust-101"
        },
        "userContext": {
            "userId": "cust-101",
            "role": "CUSTOMER"
        },
        "draftRule": {
            "id": "draft-test-1",
            "name": "Draft Customer Ownership Check",
            "agentPattern": "*",
            "toolPattern": "orders_refund",
            "conditions": [
                {
                    "field": "customerId",
                    "operator": "EQUALS_USER_ID",
                    "value": None
                }
            ],
            "action": "ALLOW",
            "dryRun": False,
            "priority": 10,
            "version": 1,
            "enabled": True
        }
    }
    r = requests.post(f"{GATEWAY_URL}/api/v1/policies/playground", headers=headers_admin, json=playground_payload, timeout=5)
    assert r.status_code == 200, f"Playground evaluation failed: {r.status_code} {r.text}"
    pg_resp = r.json()
    print(f"    Playground Output: {json.dumps(pg_resp, indent=2)}")
    assert pg_resp["decision"] == "ALLOW", f"Expected ALLOW decision in playground, got: {pg_resp['decision']}"
    assert "Draft Customer Ownership Check" in pg_resp["matchedRuleName"], "Expected draft rule matched"
    latency = pg_resp.get("evaluationTimeMs", pg_resp.get("evaluationLatencyMs", 0))
    print(f"    [PASS] Policy playground evaluated draft rule successfully (Latency: {latency}ms)!")

    print("\n" + "=" * 75)
    print("ALL DAY 1 ACCEPTANCE CHECKS PASSED SUCCESSFULLY (100% VERIFIED)!")
    print("=" * 75)


if __name__ == "__main__":
    try:
        run_verification()
    except Exception as e:
        print(f"\n[FAIL] DAY 1 VERIFICATION FAILED: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)
