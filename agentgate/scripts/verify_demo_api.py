import json
import re
import subprocess
import time
import uuid

import requests
from local_config import credential
from verify_qa1_security import get_client_token, get_user_token

DEMO = "http://localhost:8082"
GATEWAY = "http://localhost:8080"
TOKEN_HEADER = "X-AgentGate-Demo-Token"


def mcp(client_id, name, arguments):
    headers = {"Authorization": "Bearer " + get_client_token(client_id),
               "Accept": "application/json, text/event-stream"}
    with requests.Session() as session:
        def rpc(method, params):
            response = session.post(GATEWAY + "/mcp", headers=headers,
                json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=15)
            response.raise_for_status()
            if response.headers.get("Mcp-Session-Id"):
                headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
            for line in response.text.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:])
            return response.json()

        rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                           "clientInfo": {"name": "demo-api-check", "version": "1"}})
        return rpc("tools/call", {"name": name, "arguments": arguments})


def expect_problem(response, status):
    assert response.status_code == status, response.status_code
    assert response.headers["Content-Type"].startswith("application/problem+json")
    assert response.json()["status"] == status


def main():
    headers = {TOKEN_HEADER: credential("AGENTGATE_DEMO_TOKEN")}
    for method, path in [("GET", "/api/orders/demo"), ("POST", "/mcp"),
                         ("POST", "/api/webhook/approvals"), ("GET", "/v3/api-docs")]:
        expect_problem(requests.request(method, DEMO + path, timeout=10), 401)
    assert requests.get(DEMO + "/actuator/health/readiness", timeout=10).json()["status"] == "UP"
    docs = requests.get(DEMO + "/v3/api-docs", headers=headers, timeout=10)
    docs.raise_for_status()
    assert "/api/orders/{id}" in docs.json()["paths"]
    expect_problem(requests.post(DEMO + "/api/webhook/approvals", headers=headers, json={}, timeout=10), 400)
    expect_problem(requests.post(DEMO + "/api/orders/demo/refund", headers=headers,
                                 json={"amount": -1}, timeout=10), 400)
    expect_problem(requests.get(DEMO + "/api/orders/demo-fail", headers=headers, timeout=10), 500)
    print("Anonymous traffic denied; probes, authenticated OpenAPI and RFC 9457 responses verified.")
    result = mcp("agent-support-bot", "inventory_check", {"sku": "c3-demo"})["result"]
    assert not result.get("isError"), result
    print("Gateway-to-demo MCP call passed.")

    order = "c3-" + uuid.uuid4().hex
    refund = mcp("agent-finance-bot", "orders_refund", {"orderId": order, "amount": 5000})
    match = re.search(r"\bappr-[a-f0-9-]+", json.dumps(refund))
    assert match, "The high-value request did not create an approval"
    approval = match.group()
    approver = {"Authorization": "Bearer " + get_user_token("human-approver")}
    try:
        for _ in range(20):
            output = subprocess.check_output(["docker", "compose", "logs", "--since", "1m", "demo-tools"],
                                             text=True, encoding="utf-8")
            if any(approval in line and "Received approval webhook" in line for line in output.splitlines()):
                print("Gateway approval notification reached the protected demo webhook.")
                break
            time.sleep(0.5)
        else:
            raise AssertionError("No receipt for the test approval webhook")
    finally:
        rejected = requests.post(GATEWAY + f"/api/v1/approvals/{approval}/reject", headers=approver,
                                 json={"reason": "C3 verification completed"}, timeout=10)
        rejected.raise_for_status()


if __name__ == "__main__":
    main()
