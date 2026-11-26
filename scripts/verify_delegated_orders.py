import base64
import hashlib
import json
import secrets
import subprocess
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse
from uuid import UUID, uuid4

import requests
from verify_qa1_security import get_client_token, get_user_token

BASE = "http://localhost:8180"
AUTH = BASE + "/realms/agentgate/protocol/openid-connect"
GATEWAY = "http://localhost:8080"
CALLBACK = "http://127.0.0.1:8765/callback"


class Form(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.fields = {}

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "form":
            self.action = values.get("action")
        if tag == "input" and values.get("type") == "hidden" and "name" in values:
            self.fields[values["name"]] = values.get("value", "")


def user_assertion():
    realm = json.loads(Path("infra/keycloak/agentgate-realm.json").read_text())
    finance = next(c for c in realm["clients"] if c["clientId"] == "agent-finance-bot")
    user = next(u for u in realm["users"] if u["username"] == "human-user")
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    session = requests.Session()
    response = session.get(AUTH + "/auth", params={"client_id": finance["clientId"], "scope": "openid tool:delegate",
        "redirect_uri": CALLBACK, "response_type": "code", "code_challenge_method": "S256",
        "code_challenge": challenge}, timeout=10)
    for _ in range(8):
        location = response.headers.get("Location", "")
        if location.startswith(CALLBACK + "?"):
            break
        cookies = {"Cookie": "; ".join(c.name + "=" + c.value for c in session.cookies)}
        if response.is_redirect:
            assert location.startswith(BASE + "/")
            response = session.get(location, headers=cookies, allow_redirects=False, timeout=10)
        else:
            form = Form()
            form.feed(response.text)
            assert form.action
            action = urljoin(response.url, form.action)
            assert action.startswith(BASE + "/")
            fields = {"accept": "yes"} if "consent" in action else {"username": user["username"],
                "password": user["credentials"][0]["value"]}
            response = session.post(action, data={**form.fields, **fields}, headers=cookies,
                                    allow_redirects=False, timeout=10)
    assert location.startswith(CALLBACK + "?")
    token = session.post(AUTH + "/token", data={"grant_type": "authorization_code",
        "code": parse_qs(urlparse(location).query)["code"][0], "code_verifier": verifier,
        "redirect_uri": CALLBACK, "client_id": finance["clientId"], "client_secret": finance["secret"]}, timeout=10)
    token.raise_for_status()
    return token.json()["access_token"]


def sql(statement):
    return subprocess.check_output(["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
        'exec psql -X -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=statement, text=True).strip()


def main():
    assertion = user_assertion()
    subject = str(UUID(json.loads(base64.urlsafe_b64decode(assertion.split(".")[1] + "==="))["sub"]))
    headers = {"Authorization": "Bearer " + get_client_token("agent-finance-bot"),
               "Accept": "application/json, text/event-stream"}
    def rpc(method, params):
        response = requests.post(GATEWAY + "/mcp", headers=headers,
            json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=12)
        response.raise_for_status()
        if response.headers.get("Mcp-Session-Id"):
            headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
        for line in response.text.splitlines():
            if line.startswith("data:"):
                return json.loads(line[5:])["result"]
        return response.json()["result"]
    rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                       "clientInfo": {"name": "delegation-check", "version": "1"}})
    assert rpc("tools/list", {})["tools"]
    print("Agent-only discovery passed.")
    def lookup(order):
        return rpc("tools/call", {"name": "orders_get", "arguments": {"orderId": order}})
    own, other = str(uuid4()), str(uuid4())
    rule = "fr3-" + uuid4().hex
    admin = {"Authorization": "Bearer " + get_user_token("human-admin")}
    sql(f"INSERT INTO demo_order_owners VALUES ('{own}','{subject}'),('{other}','{uuid4()}');")
    created = False
    try:
        headers["X-User-Assertion"] = assertion
        result = lookup(own)
        assert not result.get("isError"), result
        assert lookup(other).get("isError")
        rows = sql(f"SELECT agent_id,user_id,policy_rule_id,outcome FROM audit_log WHERE user_id='{subject}' AND (redacted_arguments_json LIKE '%{own}%' OR redacted_arguments_json LIKE '%{other}%') ORDER BY sequence_number;")
        assert "agent-finance-bot|" + subject in rows and "pol-read-orders" in rows and "SUCCESS" in rows and "DEN" in rows, rows
        lookup_start = time.monotonic()
        for order_id, outcome, expected_rule in ((own, "SUCCESS", "pol-read-orders"), (other, "BLOCKED", "DEFAULT_DENY")):
            response = requests.get(GATEWAY + "/api/v1/admin/audit", headers=admin, timeout=10,
                                    params={"agentId": "agent-finance-bot", "toolName": "orders_get",
                                            "outcome": outcome, "size": 100})
            response.raise_for_status()
            assert any(row["userId"] == subject and row["policyRuleId"] == expected_rule
                       and json.loads(row["redactedArgumentsJson"]).get("orderId") == order_id
                       for row in response.json()["content"])
        elapsed = time.monotonic() - lookup_start
        assert elapsed < 60
        print(f"QA3 authenticated lookup of both decisions took {elapsed:.3f}s.")
        print("Own-order allow and cross-customer denial passed. Audit records:", rows)
        policy = {"id": rule, "name": rule, "agentPattern": "agent-finance-bot", "toolPattern": "orders_get",
                  "conditions": [{"field":"orderId","operator":"EQ","value":own}],
                  "priority": 1, "action":"DENY","enabled":True,"version":1,"dryRun":False}
        for version in (1,2):
            policy.update(version=version, dryRun=version==2)
            start=time.monotonic()
            saved=requests.post(GATEWAY+"/api/v1/policies",headers=admin,json=policy,timeout=10)
            saved.raise_for_status()
            created=True
            assert saved.json()["version"]==version
            assert bool(lookup(own).get("isError"))==(version==1)
            elapsed=time.monotonic()-start
            assert elapsed<10
            print(f"Policy version {version} observed in {elapsed:.2f}s.")
        logs=subprocess.check_output(["docker","compose","logs","--since","2m","gateway"],text=True,encoding="utf-8")
        assert any("[POLICY_DRY_RUN]" in line and rule in line and "DENY" in line for line in logs.splitlines())
        print("Dry-run logged the would-be denial.")
    finally:
        if created:
            requests.delete(GATEWAY+"/api/v1/policies/"+rule,headers=admin,timeout=10).raise_for_status()
        sql(f"DELETE FROM demo_order_owners WHERE order_id IN ('{own}','{other}');")


if __name__=="__main__":
    main()
