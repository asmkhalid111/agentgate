# AgentGate

AgentGate is a CSE 444 governance gateway for AI agents' MCP and REST tool calls. Every tool call passes through a single governed pipeline (`ToolExecutionPipeline`) that authenticates the calling agent (and optional `azp`-bound user delegation), evaluates default-deny policies, enforces schema/PII/truncation guards and Redis rate/write caps, and records a SHA-256 hash-chained audit trail in PostgreSQL.

**Example — Mock Refund Approval:** When `agent-finance-bot` calls `orders_refund` for `2,500.0` BDT (above the `2,000` BDT threshold), AgentGate blocks downstream execution, creates a `PENDING` approval (`REQUIRE_APPROVAL`), notifies the approval webhook, and holds the call until a human with the `human-approver` role approves, edits, or rejects it at `http://localhost:8080/approvals`.

> **[Read the Architecture Rationale](docs/architecture/ARCHITECTURE_RATIONALE.md)** — modular monolith design, `ToolExecutionPipeline`, Keycloak `azp`-bound delegation, SHA-256 audit chaining, and risk-aware policy fallback.

## Verification & Source Provenance

- **Latest local verification (`.\mvnw.cmd -B verify` at `85f8a5b` / `bf5b0ee`):** **314 tests passed** (`0` failures, `0` errors, `0` skips) — `gateway` Surefire (`310` tests including `6/6` ArchUnit rules), `gateway` Failsafe (`2` Testcontainers ITs including real-PostgreSQL QA4 policy-read outage), and `demo-tools` (`2` tests).
- **Earlier private hosted CI run (`3c5fad4` on private `review/agentgate-ci`):** **308 Maven tests passed**, QA1 `38/38` malicious blocked/held and `0/200` false positives, QA3 `0.051 s` lookup, and QA5 automated rehearsal `0.36 s`.
- **QA2 Performance (Failed & Deferred):** The hosted k6 run at `3c5fad4` measured **423.45 ms p95** gateway overhead with **873 dropped iterations** at 100 calls/sec against the **30 ms p95** target (primarily due to serialized audit chain-head updates). See [Quality evidence](docs/quality/QA_EVIDENCE_REPORT.md).
- **QA4 Fail-Safe (Verified for policy-read outage):** Verified on real `postgres:16-alpine` + Flyway (`PolicyStoreOutagePostgresIT`); a full outage of the shared PostgreSQL instance intentionally fails closed to preserve FR7 audit durability and FR8 kill-switch safety. See [QA4 verification](docs/quality/QA4_POLICY_STORE_OUTAGE_VERIFICATION.md).

## Start the Demo

Use Docker Desktop with Linux containers and open a terminal in the AgentGate repository root (`compose.yaml` directory).

1. **Generate local configuration** (creates `.env` with fresh local PostgreSQL and Keycloak credentials; keeps values private and preserves an existing `.env`):

~~~console
python scripts/init_local_env.py
~~~

On an existing checkout, run `python scripts/init_local_env.py --add-missing` to add newly required keys.

2. **Start the Compose stack** (builds `gateway` and `demo-tools` from source with Maven and starts PostgreSQL, Redis, and Keycloak):

~~~console
docker compose up --build -d --wait
~~~

3. **Verify readiness:**

~~~console
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8082/actuator/health/readiness
~~~

Both endpoints return `{"status":"UP"}`.

4. **Trigger a pending mock refund and open the approval page:**

~~~console
python -c "import requests, sys; sys.path.insert(0, 'scripts'); from verify_qa1_security import get_client_token; h = {'Authorization': 'Bearer ' + get_client_token('agent-finance-bot'), 'Accept': 'application/json, text/event-stream'}; r1 = requests.post('http://localhost:8080/mcp', headers=h, json={'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'demo','version':'1'}}}, timeout=10); h.update({'Mcp-Session-Id': r1.headers['Mcp-Session-Id']} if r1.headers.get('Mcp-Session-Id') else {}); r2 = requests.post('http://localhost:8080/mcp', headers=h, json={'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'orders_refund','arguments':{'orderId':'ORD-DEMO-01','amount':2500.0}}}, timeout=10); print(r2.text)"
~~~

Open `http://localhost:8080/approvals` in a browser and sign in at Keycloak (`localhost:8180`) as `human-approver` using `KEYCLOAK_APPROVER_PASSWORD` from your local `.env` (keep credentials off screen). Locate `ORD-DEMO-01` and approve or reject it.

- Use `/mcp` on the gateway (`localhost:8080`) for governed agent requests, `/v3/api-docs` and `/api/v1/admin/audit` with the administrator role, and `localhost:8082/v3/api-docs` with the `X-AgentGate-Demo-Token` header from `.env`.
- Use the [QA5 walkthrough](docs/adoption/QA5_WALKTHROUGH.md) for the independent onboarding trial.

## Key Documentation

- [Architecture rationale](docs/architecture/ARCHITECTURE_RATIONALE.md)
- [Architecture diagrams (C4)](docs/architecture/C4_DIAGRAMS.md)
- [Pattern justification](docs/architecture/PATTERN_MAP.md)
- [Requirements matrix](docs/governance/REQUIREMENTS_MATRIX.md)
- [Quality evidence report](docs/quality/QA_EVIDENCE_REPORT.md)
- [Live defence runbook](docs/defence/DEFENCE_REHEARSAL.md)
- [Start here](START_HERE.md) · [Roadmap](docs/governance/ROADMAP.md)
