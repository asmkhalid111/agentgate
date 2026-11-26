"""Exercise the QA5 technical steps. The timed independent participant trial is separate."""
from contextlib import contextmanager
import json
import time
import uuid

import requests
from verify_qa1_security import get_client_token, get_user_token


@contextmanager
def registered_read_tool(port, downstream, name, path="/actuator/health/readiness", parameters=None):
    api = f"http://localhost:{port}/api/v1"
    headers = {"Authorization": "Bearer " + get_user_token("human-admin")}
    policy = "verification-" + uuid.uuid4().hex
    spec = {"openapi": "3.0.0", "info": {"title": "Demo readiness", "version": "1"},
            "paths": {path: {"get": {"operationId": name, "parameters": parameters or [],
                "responses": {"200": {"description": "Readiness"}}}}}}
    def post(path, body):
        response = requests.post(api + path, headers=headers, json=body, timeout=15)
        response.raise_for_status()
        return response

    existing = requests.get(api + "/registry/tools/" + name, headers=headers, timeout=10)
    if existing.status_code != 404:
        raise RuntimeError("Verification requires an unused tool name")
    registered = post("/registry/tools/import-openapi", {"spec": json.dumps(spec),
                      "baseUrl": downstream, "owningTeam": "QA5"}).json()[0]
    created_policy = False
    try:
        assert registered["riskLevel"] == "LOW"
        post("/registry/tools/" + name + "/permissions", {"agentId": "agent-support-bot"})
        post("/policies", {"id": policy, "name": policy, "agentPattern": "agent-support-bot",
             "toolPattern": name, "conditions": [], "action": "ALLOW", "dryRun": False,
             "priority": 10, "version": 1, "enabled": True})
        created_policy = True
        yield get_client_token("agent-support-bot")
    finally:
        try:
            if created_policy:
                requests.delete(api + "/policies/" + policy, headers=headers, timeout=30).raise_for_status()
        finally:
            requests.delete(api + "/registry/tools/" + registered["id"], headers=headers, timeout=30).raise_for_status()


def main():
    start = time.monotonic()
    name = "qa5_readiness_" + uuid.uuid4().hex[:12]
    with registered_read_tool(8080, "http://demo-tools:8082", name) as token:
        headers = {"Authorization": "Bearer " + token, "Accept": "application/json, text/event-stream"}
        def rpc(method, params):
            response = requests.post("http://localhost:8080/mcp", headers=headers, timeout=15,
                                     json={"jsonrpc":"2.0","id":1,"method":method,"params":params})
            response.raise_for_status()
            if "Mcp-Session-Id" in response.headers:
                headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
            for line in response.text.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:])["result"]
            return response.json()["result"]
        rpc("initialize", {"protocolVersion":"2025-03-26","capabilities":{},
                           "clientInfo":{"name":"qa5","version":"1"}})
        result = rpc("tools/call", {"name":name,"arguments":{}})
        assert not result.get("isError") and json.loads(result["content"][0]["text"])["status"] == "UP"
        elapsed = time.monotonic() - start
        print(f"Automated onboarding rehearsal: {elapsed:.2f} seconds")
        assert elapsed < 600


if __name__ == "__main__":
    main()
