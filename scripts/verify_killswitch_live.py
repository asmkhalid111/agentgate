import urllib.request
import urllib.parse
import urllib.error
import json
import subprocess
import time
import sys

KEYCLOAK_TOKEN_URL = "http://localhost:8180/realms/agentgate/protocol/openid-connect/token"
GATEWAY_URL = "http://localhost:8080"
GATEWAY_MCP_URL = "http://localhost:8080/mcp"
ADMIN_KS_URL = "http://localhost:8080/api/v1/admin/kill-switches"
APPROVALS_URL = "http://localhost:8080/api/v1/approvals"

def get_client_token(client_id, client_secret):
    data = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret
    }).encode("utf-8")
    req = urllib.request.Request(KEYCLOAK_TOKEN_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))["access_token"]

def get_user_token(username, password, client_id="agentgate-web", client_secret="web_dev_secret_123"):
    data = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": client_id,
        "client_secret": client_secret,
        "username": username,
        "password": password
    }).encode("utf-8")
    req = urllib.request.Request(KEYCLOAK_TOKEN_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))["access_token"]

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
    req = urllib.request.Request(GATEWAY_MCP_URL, data=json.dumps(call_payload).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)

    with urllib.request.urlopen(req) as resp:
        raw = resp.read().decode("utf-8")
        return parse_sse(raw) or {}

def init_mcp_session(token):
    init_payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "live-killswitch-verifier", "version": "1.0.0"}
        }
    }
    req = urllib.request.Request(GATEWAY_MCP_URL, data=json.dumps(init_payload).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")

    with urllib.request.urlopen(req) as resp:
        return resp.headers.get("Mcp-Session-Id")

def query_pg(sql):
    cmd = ["docker", "exec", "agentgate-postgres", "psql", "-U", "agentgate", "-d", "agentgate_db", "-t", "-A", "-c", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return result.stdout.strip()

def main():
    print("=" * 70)
    print("AgentGate FR8 Task 12B: Live Docker Emergency Kill Switch Verification")
    print("=" * 70)

    # 1. Authenticate tokens
    print("\n[Step 1] Authenticating principals against Keycloak...")
    admin_token = get_user_token("human-admin", "admin_dev_password_123")
    print("  ✓ human-admin token acquired (Role: ADMIN)")

    approver_token = get_user_token("human-approver", "approver_dev_password_123")
    print("  ✓ human-approver token acquired (Role: APPROVER)")

    agent_token = get_client_token("agent-finance-bot", "finance_dev_secret_123")
    print("  ✓ agent-finance-bot service-account token acquired (Role: AGENT)")

    # 2. RBAC verification on Admin Kill Switch endpoint
    print("\n[Step 2] Verifying RBAC protection on /api/v1/admin/kill-switches...")
    for role_name, t in [("human-approver", approver_token), ("agent-finance-bot", agent_token)]:
        req = urllib.request.Request(ADMIN_KS_URL, method="GET")
        req.add_header("Authorization", f"Bearer {t}")
        try:
            with urllib.request.urlopen(req) as resp:
                print(f"  ✗ FAILED: Expected 403 Forbidden for {role_name}, got {resp.status}")
                sys.exit(1)
        except urllib.error.HTTPError as e:
            if e.code == 403:
                print(f"  ✓ {role_name} correctly rejected with HTTP 403 Forbidden")
            else:
                print(f"  ✗ Unexpected HTTP status for {role_name}: {e.code}")
                sys.exit(1)

    # 3. Verify human-admin can access admin endpoint
    req_admin = urllib.request.Request(ADMIN_KS_URL, method="GET")
    req_admin.add_header("Authorization", f"Bearer {admin_token}")
    with urllib.request.urlopen(req_admin) as resp:
        assert resp.status == 200
        print("  ✓ human-admin successfully accessed kill switch administration (HTTP 200)")

    # 4. Clean up any existing kill switches for a clean test
    print("\n[Step 3] Resetting kill switches to clean initial state...")
    query_pg("DELETE FROM kill_switches WHERE target = 'orders_refund' OR target = 'agent-finance-bot';")
    print("  ✓ Cleared existing test kill switches in PostgreSQL")

    # 5. Initialize MCP session as agent-finance-bot
    print("\n[Step 4] Establishing MCP session for agent-finance-bot...")
    session_id = init_mcp_session(agent_token)
    print(f"  ✓ MCP Session initialized: {session_id}")

    # 6. Trigger high-risk tool call requiring human approval
    print("\n[Step 5] Triggering high-risk tool call (orders_refund, amount=5000)...")
    res_refund = mcp_call(
        agent_token,
        session_id,
        "orders_refund",
        {"orderId": "ORD-LIVE-KS-001", "amount": 5000, "reason": "damaged shipment"},
        req_id=101
    )
    refund_text = res_refund.get("result", {}).get("content", [{}])[0].get("text", "")
    print(f"  ✓ MCP response: {refund_text}")
    assert "REQUIRE_APPROVAL" in refund_text, f"Expected REQUIRE_APPROVAL, got: {refund_text}"

    # Extract approvalId
    import re
    match = re.search(r'appr-[0-9a-fA-F-]+', refund_text)
    assert match, f"Could not extract approvalId from: {refund_text}"
    approval_id = match.group(0)
    print(f"  ✓ Extracted approvalId: {approval_id}")

    # Verify PENDING status in DB
    db_status = query_pg(f"SELECT status FROM approval_requests WHERE id = '{approval_id}';")
    print(f"  ✓ Database status for {approval_id}: {db_status}")
    assert db_status == "PENDING", f"Expected PENDING in DB, got: {db_status}"

    # 7. Activate Emergency Kill Switch as human-admin
    print("\n[Step 6] Activating emergency TOOL kill switch for orders_refund as human-admin...")
    activate_payload = json.dumps({
        "type": "TOOL",
        "target": "orders_refund",
        "reason": "Security Alert SEC-2026-LIVE: Emergency stop on refund tool"
    }).encode("utf-8")
    req_act = urllib.request.Request(ADMIN_KS_URL, data=activate_payload, method="POST")
    req_act.add_header("Authorization", f"Bearer {admin_token}")
    req_act.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req_act) as resp:
        act_body = json.loads(resp.read().decode("utf-8"))
        print(f"  ✓ Kill switch activated: id={act_body.get('id')}, type={act_body.get('type')}, target={act_body.get('target')}, active={act_body.get('active')}")
        assert act_body.get("active") is True

    # 8. Verify atomic cancellation in PostgreSQL
    print("\n[Step 7] Verifying atomic cancellation in PostgreSQL...")
    db_after_cancel = query_pg(f"SELECT status, decided_by FROM approval_requests WHERE id = '{approval_id}';")
    print(f"  ✓ Approval record in DB: {db_after_cancel}")
    status_val, decided_by_val = db_after_cancel.split("|")
    assert status_val == "CANCELLED", f"Expected CANCELLED status, got {status_val}"
    assert decided_by_val == "human-admin", f"Expected decided_by=human-admin, got {decided_by_val}"

    # 9. Verify get_approval_result MCP tool returns CANCELLED with no secret leak
    print("\n[Step 8] Verifying get_approval_result via MCP for originating agent...")
    res_get_appr = mcp_call(
        agent_token,
        session_id,
        "get_approval_result",
        {"approvalId": approval_id},
        req_id=102
    )
    result_content = res_get_appr.get("result", {}).get("content", [{}])[0].get("text", "")
    print(f"  ✓ get_approval_result MCP response: {result_content}")
    parsed_result = json.loads(result_content)
    assert parsed_result.get("status") == "CANCELLED", f"Expected CANCELLED, got {parsed_result.get('status')}"
    assert parsed_result.get("message") == "Approval request was cancelled", f"Expected generic message, got {parsed_result.get('message')}"
    assert "Security Alert SEC-2026-LIVE" not in result_content, "CRITICAL: Administrative reason leaked to machine agent!"
    assert parsed_result.get("result") is None, "Result must be null for cancelled request"
    print("  ✓ Status CANCELLED confirmed; no internal administrative reason or credentials disclosed")

    # 10. Attempt human approval on CANCELLED request
    print("\n[Step 9] Attempting approval by human-approver on CANCELLED request...")
    approve_url = f"{APPROVALS_URL}/{approval_id}/approve"
    req_approve = urllib.request.Request(approve_url, data=b"{}", method="POST")
    req_approve.add_header("Authorization", f"Bearer {approver_token}")
    req_approve.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req_approve) as resp:
            print(f"  ✗ FAILED: Expected 409 Conflict, got {resp.status}")
            sys.exit(1)
    except urllib.error.HTTPError as e:
        if e.code == 409:
            body = json.loads(e.read().decode("utf-8"))
            print(f"  ✓ Correctly rejected with HTTP 409 Conflict: {body.get('detail')}")
            assert body.get("errorCode") == "INVALID_APPROVAL_STATE"
        else:
            print(f"  ✗ Unexpected HTTP status: {e.code}")
            sys.exit(1)

    # 11. Ingress check under active kill switch
    print("\n[Step 10] Attempting new tool execution while switch is active...")
    res_blocked = mcp_call(
        agent_token,
        session_id,
        "orders_refund",
        {"orderId": "ORD-LIVE-KS-002", "amount": 100, "reason": "small refund under killswitch"},
        req_id=103
    )
    blocked_text = res_blocked.get("result", {}).get("content", [{}])[0].get("text", "")
    print(f"  ✓ Blocked tool response: {blocked_text}")
    assert "KILL_SWITCH_ACTIVE" in blocked_text, f"Expected KILL_SWITCH_ACTIVE, got {blocked_text}"
    assert "orders_refund" in blocked_text

    # 12. Deactivate kill switch and verify non-revival
    print("\n[Step 11] Deactivating emergency kill switch as human-admin...")
    deact_url = f"{ADMIN_KS_URL}/TOOL/orders_refund"
    req_deact = urllib.request.Request(deact_url, method="DELETE")
    req_deact.add_header("Authorization", f"Bearer {admin_token}")
    with urllib.request.urlopen(req_deact) as resp:
        deact_body = json.loads(resp.read().decode("utf-8"))
        print(f"  ✓ Switch deactivated: active={deact_body.get('active')}")
        assert deact_body.get("active") is False

    # Check database: old request MUST still be CANCELLED
    db_after_deact = query_pg(f"SELECT status FROM approval_requests WHERE id = '{approval_id}';")
    print(f"  ✓ Previous approval request in DB remains: {db_after_deact}")
    assert db_after_deact == "CANCELLED", f"Cancelled request was revived! Got: {db_after_deact}"

    # Verify legitimate calls are unblocked after deactivation
    print("\n[Step 12] Verifying subsequent tool calls succeed after deactivation...")
    res_unblocked = mcp_call(
        agent_token,
        session_id,
        "orders_refund",
        {"orderId": "ORD-LIVE-KS-003", "amount": 1500, "reason": "standard refund"},
        req_id=104
    )
    unblocked_text = res_unblocked.get("result", {}).get("content", [{}])[0].get("text", "")
    print(f"  ✓ Post-deactivation call response: {unblocked_text}")
    assert "KILL_SWITCH_ACTIVE" not in unblocked_text, f"Call still blocked: {unblocked_text}"

    print("\n" + "=" * 70)
    print("ALL 12 LIVE VERIFICATION STEPS PASSED SUCCESSFULLY!")
    print("Emergency Kill Switches (FR8 / Task 12B) fully verified against Docker.")
    print("=" * 70)

if __name__ == "__main__":
    main()
