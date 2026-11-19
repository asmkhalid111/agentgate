import urllib.request
import urllib.parse
import urllib.error
import http.cookiejar
import json
import subprocess
import os
import re
import sys
import time

# --- Configuration & Local Credential Loading ---
KEYCLOAK_BASE_URL = os.environ.get("KEYCLOAK_BASE_URL", "http://localhost:8180")
KEYCLOAK_TOKEN_URL = f"{KEYCLOAK_BASE_URL}/realms/agentgate/protocol/openid-connect/token"
KEYCLOAK_AUTH_URL = f"{KEYCLOAK_BASE_URL}/realms/agentgate/protocol/openid-connect/auth"
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:8080")
GATEWAY_MCP_URL = f"{GATEWAY_URL}/mcp"
ADMIN_KS_URL = f"{GATEWAY_URL}/api/v1/admin/kill-switches"
APPROVALS_URL = f"{GATEWAY_URL}/api/v1/approvals"
OIDC_REDIRECT_URI = f"{GATEWAY_URL}/login/oauth2/code/keycloak"

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

WEB_CLIENT_SECRET = os.environ.get("KEYCLOAK_WEB_CLIENT_SECRET", "web_dev_secret_123")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_HUMAN_ADMIN_PASSWORD", os.environ.get("HUMAN_ADMIN_PASSWORD", "admin_dev_password_123"))
APPROVER_PASSWORD = os.environ.get("KEYCLOAK_APPROVER_PASSWORD", "approver_dev_password_123")
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
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))["access_token"]

def get_user_token_via_auth_code(username, password, client_id="agentgate-web", client_secret=WEB_CLIENT_SECRET):
    """
    Standard OIDC Authorization Code Flow for human users.
    Preserves directAccessGrantsEnabled=false on agentgate-web client.
    """
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))

    # 1. Authorization Request
    auth_url = (
        f"{KEYCLOAK_AUTH_URL}?"
        + urllib.parse.urlencode({
            "client_id": client_id,
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": OIDC_REDIRECT_URI
        })
    )

    req = urllib.request.Request(auth_url)
    resp1 = opener.open(req)
    html = resp1.read().decode("utf-8")

    match = re.search(r'action="([^"]+)"', html)
    if not match:
        raise RuntimeError("No login form action found in Keycloak auth response: " + html[:300])

    action_url = match.group(1).replace("&amp;", "&")

    # 2. Submit credentials to Keycloak login form
    cookie_header = "; ".join([f"{c.name}={c.value}" for c in cookie_jar])
    login_data = urllib.parse.urlencode({
        "username": username,
        "password": password
    }).encode("utf-8")

    login_req = urllib.request.Request(action_url, data=login_data, method="POST")
    login_req.add_header("Cookie", cookie_header)
    login_req.add_header("Content-Type", "application/x-www-form-urlencoded")

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def http_error_302(self, req, fp, code, msg, headers):
            return fp

    no_redirect_opener = urllib.request.build_opener(NoRedirect)
    resp2 = no_redirect_opener.open(login_req)
    loc = resp2.headers.get("Location")
    if not loc:
        raise RuntimeError(f"Expected 302 redirect from Keycloak login, got: {resp2.headers}")

    code_match = re.search(r'code=([^&]+)', loc)
    if not code_match:
        raise RuntimeError(f"No authorization code in redirect Location: {loc}")

    code = code_match.group(1)

    # 3. Exchange authorization code for tokens
    token_data = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "client_id": client_id,
        "client_secret": client_secret,
        "code": code,
        "redirect_uri": OIDC_REDIRECT_URI
    }).encode("utf-8")

    token_req = urllib.request.Request(KEYCLOAK_TOKEN_URL, data=token_data, method="POST")
    token_req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(token_req) as token_resp:
        token_body = json.loads(token_resp.read().decode("utf-8"))
        return token_body["access_token"]

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

    # 1. Authenticate tokens via standard OIDC Auth Code flow for humans, M2M for agent
    print("\n[Step 1] Authenticating principals against Keycloak...")
    admin_token = get_user_token_via_auth_code("human-admin", ADMIN_PASSWORD)
    print("  ✓ human-admin token acquired via OIDC Authorization Code Flow (Role: ADMIN)")

    approver_token = get_user_token_via_auth_code("human-approver", APPROVER_PASSWORD)
    print("  ✓ human-approver token acquired via OIDC Authorization Code Flow (Role: APPROVER)")

    agent_token = get_client_token("agent-finance-bot", AGENT_CLIENT_SECRET)
    print("  ✓ agent-finance-bot token acquired via Client Credentials Grant (Role: AGENT)")

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

    # 4. Clean up ONLY test-created kill switches for orders_refund
    print("\n[Step 3] Resetting test-specific kill switch in PostgreSQL...")
    query_pg("DELETE FROM kill_switches WHERE type = 'TOOL' AND target = 'orders_refund';")
    print("  ✓ Cleared test kill switch (orders_refund) without touching unrelated controls")

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
    match = re.search(r'appr-[0-9a-fA-F-]+', refund_text)
    assert match, f"Could not extract approvalId from: {refund_text}"
    approval_id = match.group(0)
    print(f"  ✓ Extracted approvalId: {approval_id}")

    # Verify PENDING status in DB
    db_status = query_pg(f"SELECT status FROM approval_requests WHERE id = '{approval_id}';")
    print(f"  ✓ Database status for {approval_id}: {db_status}")
    assert db_status == "PENDING", f"Expected PENDING in DB, got: {db_status}"

    # 7. Activate Emergency Kill Switch as human-admin with sensitive internal incident reason
    print("\n[Step 6] Activating emergency TOOL kill switch for orders_refund as human-admin...")
    sensitive_reason = "INCIDENT #SEC-9982: Suspected fraudulent refund storm detected by admin.sec"
    activate_payload = json.dumps({
        "type": "TOOL",
        "target": "orders_refund",
        "reason": sensitive_reason
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
    assert "INCIDENT" not in result_content, "CRITICAL: Incident notes leaked to machine agent!"
    assert "admin.sec" not in result_content, "CRITICAL: Admin username leaked to machine agent!"
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

    # 11. Ingress check under active kill switch: verify exact generic error
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
    expected_error = "KILL_SWITCH_ACTIVE: Tool execution is disabled by an emergency control."
    assert expected_error in blocked_text, f"Expected generic error '{expected_error}', got: {blocked_text}"
    assert "INCIDENT" not in blocked_text, "CRITICAL: Sensitive incident note leaked in tool response!"
    assert "admin.sec" not in blocked_text, "CRITICAL: Admin username leaked in tool response!"
    print("  ✓ Generic KILL_SWITCH_ACTIVE error verified; zero information disclosure confirmed")

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

    # 13. Test isolation cleanup: clean up ONLY the test approval request
    print("\n[Step 13] Cleaning up test-created approval request...")
    query_pg(f"DELETE FROM approval_requests WHERE id = '{approval_id}';")
    print(f"  ✓ Removed test approval {approval_id}; real records untouched")

    print("\n" + "=" * 70)
    print("ALL 13 LIVE VERIFICATION STEPS PASSED SUCCESSFULLY!")
    print("Emergency Kill Switches (FR8 / Task 12B Corrections) fully verified against Docker.")
    print("=" * 70)

if __name__ == "__main__":
    main()
