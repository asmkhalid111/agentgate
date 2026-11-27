#!/usr/bin/env python3
"""
Generates deterministic QA1 security acceptance test vectors for AgentGate.
Output: scripts/data/qa1_test_vectors.json
Dataset:
  - 38 Malicious test cases covering AUTH, SCOPE, DISC, PERM, SCHM, POL, APPR, ADMIN, BYPASS, DELEG, SENS.
  - Exactly 200 Legitimate test cases covering L1 (Echo & Status), L2 (Governed High-Risk Holds),
    L3 (Approval Queries), L4 (Human Approver REST), L5 (Policy Playground Evaluations).
"""

import json
import os

def build_malicious_vectors():
    vectors = [
        # AUTH (1-5)
        {
            "id": "AUTH-01",
            "category": "AUTH",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_status",
            "caller": "ANONYMOUS",
            "arguments": {"message": "unauthenticated ping"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "Unauthenticated MCP invocation without Authorization header"
        },
        {
            "id": "AUTH-02",
            "category": "AUTH",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_status",
            "caller": "EXPIRED_TOKEN",
            "arguments": {"message": "expired ping"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "MCP invocation with expired JWT bearer token"
        },
        {
            "id": "AUTH-03",
            "category": "AUTH",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_status",
            "caller": "TAMPERED_TOKEN",
            "arguments": {"message": "tampered ping"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "MCP invocation with tampered cryptographic signature"
        },
        {
            "id": "AUTH-04",
            "category": "AUTH",
            "type": "MALICIOUS",
            "target_type": "REST_GET",
            "endpoint": "/api/v1/approvals",
            "caller": "ANONYMOUS",
            "arguments": {},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "Unauthenticated REST call to approvals management endpoint"
        },
        {
            "id": "AUTH-05",
            "category": "AUTH",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/admin/kill-switches",
            "caller": "ANONYMOUS",
            "arguments": {"agentId": "agent-finance-bot"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "Unauthenticated REST call to emergency kill switch endpoint"
        },

        # SCOPE (6-8)
        {
            "id": "SCOPE-01",
            "category": "SCOPE",
            "type": "MALICIOUS",
            "target_type": "MCP_LIST",
            "endpoint": "/mcp",
            "tool": None,
            "caller": "agent-unauthorized-bot",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_SCOPE",
            "description": "Agent lacking agent:invoke scope attempting MCP access"
        },
        {
            "id": "SCOPE-02",
            "category": "SCOPE",
            "type": "MALICIOUS",
            "target_type": "REST_GET",
            "endpoint": "/api/v1/registry/tools",
            "caller": "agent-support-bot",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Agent lacking tool:admin attempting tool registry inspection"
        },
        {
            "id": "SCOPE-03",
            "category": "SCOPE",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/policies",
            "caller": "agent-finance-bot",
            "arguments": {"name": "Permissive Policy"},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Agent lacking tool:admin attempting policy mutation"
        },

        # DISC (9-10)
        {
            "id": "DISC-01",
            "category": "DISC",
            "type": "MALICIOUS",
            "target_type": "MCP_LIST",
            "endpoint": "/mcp",
            "tool": None,
            "caller": "agent-support-bot",
            "arguments": {},
            "expected_http_status": 200,
            "expected_decision": "TOOL_HIDDEN",
            "forbidden_tools": ["orders_refund", "m01_restricted_admin_tool"],
            "description": "Support agent discovery filtering hiding unpermitted tools"
        },
        {
            "id": "DISC-02",
            "category": "DISC",
            "type": "MALICIOUS",
            "target_type": "MCP_LIST",
            "endpoint": "/mcp",
            "tool": None,
            "caller": "agent-finance-bot",
            "arguments": {},
            "expected_http_status": 200,
            "expected_decision": "TOOL_HIDDEN",
            "forbidden_tools": ["m01_restricted_admin_tool"],
            "description": "Finance agent discovery filtering hiding restricted admin tool (least privilege)"
        },

        # PERM (11-14)
        {
            "id": "PERM-01",
            "category": "PERM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "ORD-MAL-01", "amount": 100.0},
            "expected_http_status": 200,
            "expected_decision": "TOOL_UNAUTHORIZED",
            "description": "Support agent attempting direct invocation of orders_refund"
        },
        {
            "id": "PERM-02",
            "category": "PERM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_restricted_admin_tool",
            "caller": "agent-support-bot",
            "arguments": {"action": "database_dump"},
            "expected_http_status": 200,
            "expected_decision": "TOOL_UNAUTHORIZED",
            "description": "Support agent attempting direct invocation of m01_restricted_admin_tool"
        },
        {
            "id": "PERM-03",
            "category": "PERM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_restricted_admin_tool",
            "caller": "agent-finance-bot",
            "arguments": {"action": "database_dump"},
            "expected_http_status": 200,
            "expected_decision": "TOOL_UNAUTHORIZED",
            "description": "Finance agent attempting invocation of restricted admin tool (least privilege)"
        },
        {
            "id": "PERM-04",
            "category": "PERM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "database_query_tool",
            "caller": "agent-finance-bot",
            "arguments": {"sql": "SELECT * FROM users"},
            "expected_http_status": 200,
            "expected_decision": "TOOL_UNKNOWN",
            "description": "Direct invocation of unregistered tool"
        },

        # SCHM (15-19)
        {
            "id": "SCHM-01",
            "category": "SCHM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "ORD-SCHM-01"},
            "expected_http_status": 200,
            "expected_decision": "SCHEMA_VALIDATION_FAILED",
            "description": "Schema violation: missing required property 'amount'"
        },
        {
            "id": "SCHM-02",
            "category": "SCHM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"amount": 500.0},
            "expected_http_status": 200,
            "expected_decision": "SCHEMA_VALIDATION_FAILED",
            "description": "Schema violation: missing required property 'orderId'"
        },
        {
            "id": "SCHM-03",
            "category": "SCHM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "ORD-SCHM-03", "amount": "invalid_five_hundred"},
            "expected_http_status": 200,
            "expected_decision": "SCHEMA_VALIDATION_FAILED",
            "description": "Schema type violation: string passed for numeric amount"
        },
        {
            "id": "SCHM-04",
            "category": "SCHM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": {"nested": "complex_object"}, "amount": 100.0},
            "expected_http_status": 200,
            "expected_decision": "SCHEMA_VALIDATION_FAILED",
            "description": "Schema type violation: object passed for string orderId"
        },
        {
            "id": "SCHM-05",
            "category": "SCHM",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {},
            "expected_http_status": 200,
            "expected_decision": "SCHEMA_VALIDATION_FAILED",
            "description": "Schema violation: orders_get missing required property 'orderId'"
        },

        # POL (20-23)
        {
            "id": "POL-01",
            "category": "POL",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "ORD-POL-01", "customerId": "cust-999-victim"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "Cross-customer read attempt / M2M call without verified user context"
        },
        {
            "id": "POL-02",
            "category": "POL",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "ORD-POL-02", "customerId": "cust-victim-finance"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "Cross-customer order read attempt by finance agent blocked by policy"
        },
        {
            "id": "POL-03",
            "category": "POL",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "ORD-POL-03", "amount": 5000.0},
            "expected_http_status": 200,
            "expected_decision": "REQUIRE_APPROVAL",
            "description": "High-risk refund > 2000 BDT held for human approval (downstream held)"
        },
        {
            "id": "POL-04",
            "category": "POL",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "ORD-POL-04", "amount": 1000000.0},
            "expected_http_status": 200,
            "expected_decision": "REQUIRE_APPROVAL",
            "description": "Extreme financial drain attempt (1M BDT) held for human approval"
        },

        # APPR (24-28)
        {
            "id": "APPR-01",
            "category": "APPR",
            "type": "MALICIOUS",
            "target_type": "REST_GET",
            "endpoint": "/api/v1/approvals",
            "caller": "agent-finance-bot",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Machine agent attempting to list human approvals"
        },
        {
            "id": "APPR-02",
            "category": "APPR",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/approvals/appr-fake-123/approve",
            "caller": "agent-finance-bot",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Machine agent attempting to approve request"
        },
        {
            "id": "APPR-03",
            "category": "APPR",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/approvals/appr-fake-123/approve",
            "caller": "human-user",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Standard user lacking ROLE_APPROVER attempting approval"
        },
        {
            "id": "APPR-04",
            "category": "APPR",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "get_approval_result",
            "caller": "agent-finance-bot",
            "arguments": {"approvalId": "appr-nonexistent-9999"},
            "expected_http_status": 200,
            "expected_decision": "APPROVAL_NOT_FOUND",
            "description": "Inquiry for non-existent approval ID returning safe NOT_FOUND"
        },
        {
            "id": "APPR-05",
            "category": "APPR",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "get_approval_result",
            "caller": "agent-support-bot",
            "arguments": {"approvalId": "PREVIOUS_HOLD_1"},
            "expected_http_status": 200,
            "expected_decision": "APPROVAL_NOT_FOUND",
            "description": "Support bot attempting to inspect finance bot's approval result safely returning non-disclosing NOT_FOUND"
        },

        # ADMIN (29-31)
        {
            "id": "ADMIN-01",
            "category": "ADMIN",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/admin/kill-switches",
            "caller": "agent-finance-bot",
            "arguments": {"agentId": "agent-support-bot"},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Machine agent attempting to activate kill switch"
        },
        {
            "id": "ADMIN-02",
            "category": "ADMIN",
            "type": "MALICIOUS",
            "target_type": "REST_GET",
            "endpoint": "/api/v1/admin/audit",
            "caller": "agent-support-bot",
            "arguments": {},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Machine agent attempting to query audit logs"
        },
        {
            "id": "ADMIN-03",
            "category": "ADMIN",
            "type": "MALICIOUS",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/policies",
            "caller": "human-user",
            "arguments": {"name": "User Created Policy"},
            "expected_http_status": 403,
            "expected_decision": "FORBIDDEN_ROLE",
            "description": "Non-admin user attempting policy modification"
        },

        # BYPASS (32-35)
        {
            "id": "BYPASS-01",
            "category": "BYPASS",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "../../../../etc/passwd"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "Path traversal attempt in tool parameter safely blocked by policy"
        },
        {
            "id": "BYPASS-02",
            "category": "BYPASS",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "ORD-1' OR '1'='1; DROP TABLE registered_tools;--"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "SQL injection attempt in tool parameter safely blocked by policy"
        },
        {
            "id": "BYPASS-03",
            "category": "BYPASS",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "<script>alert('xss')</script>"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "XSS script tag injection in tool parameter safely blocked by policy"
        },
        {
            "id": "BYPASS-04",
            "category": "BYPASS",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "arguments": {"orderId": "ORD-1\u0000nullbyte"},
            "expected_http_status": 200,
            "expected_decision": "POLICY_DENIED",
            "description": "Null byte injection in tool parameter safely blocked by policy"
        },

        # DELEG (36-37)
        {
            "id": "DELEG-01",
            "category": "DELEG",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "headers": {"X-User-Id": "victim-admin"},
            "arguments": {"orderId": "ORD-DELEG-01", "customerId": "victim-admin"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "Unsigned end-user identity rejected during authentication"
        },
        {
            "id": "DELEG-02",
            "category": "DELEG",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_get",
            "caller": "agent-support-bot",
            "headers": {"X-User-Assertion": "invalid.jwt.token"},
            "arguments": {"orderId": "ORD-DELEG-02", "customerId": "victim-admin"},
            "expected_http_status": 401,
            "expected_decision": "UNAUTHENTICATED",
            "description": "Malformed/unsigned X-User-Assertion rejected by gateway"
        },

        # SENS (38)
        {
            "id": "SENS-01",
            "category": "SENS",
            "type": "MALICIOUS",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {"orderId": "fail-simulated", "amount": 100.0},
            "expected_http_status": 200,
            "expected_decision": "SANITIZED_ERROR",
            "description": "Downstream internal error simulation returning sanitized error without stack trace"
        }
    ]
    return vectors

def build_legitimate_vectors():
    vectors = []
    
    # Category L1: 100 MCP status & echo calls (50 support bot, 50 finance bot)
    # Varied messages testing strings, timestamps, business IDs, and PII redaction
    for i in range(1, 101):
        caller = "agent-support-bot" if i <= 50 else "agent-finance-bot"
        case_id = f"LEGIT-{i:03d}"
        
        if i <= 30:
            msg = f"System health verification query {i} - operational check"
            desc = f"Read-only status echo check {i}"
        elif i <= 50:
            ref_type = i % 3
            if ref_type == 0:
                msg = f"Order reference query: ORD-1732085139-{i}"
            elif ref_type == 1:
                msg = f"Refund reference query: REF-1732085139012-{i}"
            else:
                msg = f"Timestamp transaction sync: 1732085139"
            desc = f"Business identifier & timestamp integrity echo {i}"
        elif i <= 70:
            msg = f"Diagnostic probe #{i}: {{\"metric\": \"latency\", \"valid\": true, \"id\": {i}}}"
            desc = f"Structured JSON payload echo check {i}"
        elif i <= 90:
            pii_type = i % 3
            if pii_type == 0:
                msg = f"Customer NID: 1234567890 reference check {i}"
            elif pii_type == 1:
                msg = f"Contact email: user.{i}@example.com verification"
            else:
                msg = f"Mobile contact: +8801712345{i:03d} recorded"
            desc = f"PII redaction verification echo {i}"
        else:
            msg = "" if i % 2 == 0 else f"Ping {i}"
            desc = f"Minimal payload ping {i}"

        vectors.append({
            "id": case_id,
            "category": "L1_STATUS_ECHO",
            "type": "LEGITIMATE",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "m01_status",
            "caller": caller,
            "arguments": {"message": msg} if msg else {},
            "expected_decision": "SUCCESS",
            "description": desc
        })

    # Category L2: 25 Governed High-Value Refund Holds (> 2000 BDT) for agent-finance-bot
    # Expected behavior: REQUIRE_APPROVAL with ApprovalId, downstream NOT dispatched, write cap NOT consumed
    for idx in range(101, 126):
        case_id = f"LEGIT-{idx:03d}"
        hold_num = idx - 100
        amt = 2100.0 + (hold_num * 100.0)
        vectors.append({
            "id": case_id,
            "category": "L2_HIGH_REFUND_HOLD",
            "type": "LEGITIMATE",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "orders_refund",
            "caller": "agent-finance-bot",
            "arguments": {
                "orderId": f"ORD-HOLD-{hold_num:02d}",
                "amount": amt
            },
            "expected_decision": "REQUIRE_APPROVAL",
            "description": f"Governed high-value refund #{hold_num} ({amt} BDT > 2000 BDT) held for human approval"
        })

    # Category L3: 25 Governed Approval Result Queries via get_approval_result
    for idx in range(126, 151):
        case_id = f"LEGIT-{idx:03d}"
        q_num = idx - 125
        vectors.append({
            "id": case_id,
            "category": "L3_APPROVAL_INQUIRY",
            "type": "LEGITIMATE",
            "target_type": "MCP_CALL",
            "endpoint": "/mcp",
            "tool": "get_approval_result",
            "caller": "agent-finance-bot",
            "arguments": {"approvalId": f"PREVIOUS_HOLD_{q_num}"},
            "expected_decision": "APPROVAL_STATUS_PENDING",
            "description": f"Inquiry for pending approval result #{q_num}"
        })

    # Category L4: 25 REST Human Approval Queries via /api/v1/approvals
    for idx in range(151, 176):
        case_id = f"LEGIT-{idx:03d}"
        page_num = (idx - 151) % 5
        size_num = 5 + (idx % 10)
        vectors.append({
            "id": case_id,
            "category": "L4_HUMAN_APPROVAL_REST",
            "type": "LEGITIMATE",
            "target_type": "REST_GET",
            "endpoint": f"/api/v1/approvals?status=PENDING&page={page_num}&size={size_num}",
            "caller": "human-approver",
            "arguments": {},
            "expected_decision": "SUCCESS_PAGED_APPROVALS",
            "description": f"Human approver paginated query #{idx - 150} (page={page_num}, size={size_num})"
        })

    # Category L5: 25 REST Policy Playground Queries via /api/v1/policies/playground
    for idx in range(176, 201):
        case_id = f"LEGIT-{idx:03d}"
        p_num = idx - 175
        if p_num <= 10:
            uid = f"cust-user-{p_num}"
            payload = {
                "agentId": "agent-support-bot",
                "toolName": "orders_get",
                "arguments": {"customerId": uid, "orderId": f"ORD-PLAY-{p_num}"},
                "userId": uid,
                "riskLevel": "LOW"
            }
            exp_dec = "ALLOW"
            desc = f"Policy playground self-read evaluation #{p_num} (ALLOW)"
        elif p_num <= 18:
            amt = 100.0 * (p_num - 10)
            payload = {
                "agentId": "agent-finance-bot",
                "toolName": "orders_refund",
                "arguments": {"orderId": f"ORD-PLAY-REF-{p_num}", "amount": amt},
                "userId": "usr-finance",
                "riskLevel": "HIGH"
            }
            exp_dec = "ALLOW"
            desc = f"Policy playground low refund evaluation #{p_num} ({amt} BDT -> ALLOW)"
        else:
            amt = 2500.0 + (p_num - 18) * 200.0
            payload = {
                "agentId": "agent-finance-bot",
                "toolName": "orders_refund",
                "arguments": {"orderId": f"ORD-PLAY-HIGH-{p_num}", "amount": amt},
                "userId": "usr-finance",
                "riskLevel": "HIGH"
            }
            exp_dec = "REQUIRE_APPROVAL"
            desc = f"Policy playground high refund evaluation #{p_num} ({amt} BDT -> REQUIRE_APPROVAL)"

        vectors.append({
            "id": case_id,
            "category": "L5_POLICY_PLAYGROUND",
            "type": "LEGITIMATE",
            "target_type": "REST_POST",
            "endpoint": "/api/v1/policies/playground",
            "caller": "agent-finance-bot",
            "arguments": payload,
            "expected_decision": exp_dec,
            "description": desc
        })

    return vectors

def main():
    malicious = build_malicious_vectors()
    legitimate = build_legitimate_vectors()
    
    assert len(malicious) == 38, f"Expected 38 malicious vectors, got {len(malicious)}"
    assert len(legitimate) == 200, f"Expected 200 legitimate vectors, got {len(legitimate)}"
    
    dataset = {
        "metadata": {
            "version": "1.0.0",
            "description": "AgentGate QA1 Security Acceptance Test Suite Dataset",
            "malicious_count": len(malicious),
            "legitimate_count": len(legitimate),
            "total_count": len(malicious) + len(legitimate)
        },
        "malicious_vectors": malicious,
        "legitimate_vectors": legitimate
    }
    
    out_dir = os.path.join(os.path.dirname(__file__), "data")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "qa1_test_vectors.json")
    
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(dataset, f, indent=2)
    
    print(f"Generated {len(malicious)} malicious vectors and {len(legitimate)} legitimate vectors.")
    print(f"Saved to: {out_path}")

if __name__ == "__main__":
    main()
