# C12 Live Defence Runbook (15 Minutes)

**Baseline:** `master` @ `a0018fa` (tested CI revision `3c5fad4` on `review/agentgate-ci`).  
**Status:** Prepared (API path rehearsed; browser UI rejection requires manual rehearsal; live defence pending).

---

## Time Budget Overview (Main Path)

| Window | Segment | Duration | Goal |
|---|---|---:|---|
| 0:00 – 2:00 | Stack startup & readiness | 2 min | Show single-command Compose stack and `/actuator/health/readiness` |
| 2:00 – 6:00 | Pending mock refund & browser rejection | 4 min | Trigger `REQUIRE_APPROVAL` via MCP and reject in `/approvals` UI |
| 6:00 – 10:00 | Audit integrity ADR walkthrough | 4 min | Walk through [ADR-0003](../architecture/adr/0003-audit-integrity.md) and tamper detection |
| 10:00 – 15:00 | Instructor live change | 5 min | Implement and verify a small on-the-spot change |

---

## 1. Stack Startup & Readiness (0:00 – 2:00)

**Commands:**
```powershell
# Start the existing 5-service stack (or add --build to rebuild from source)
docker compose up -d --wait

# Verify protected gateway and downstream demo-tools readiness
curl.exe -s http://localhost:8080/actuator/health/readiness
curl.exe -s http://localhost:8082/actuator/health/readiness
```

**Expected output:** Both readiness endpoints return `{"status":"UP"}` and `docker compose ps` shows `agentgate-gateway`, `agentgate-demo-tools`, `agentgate-postgres`, `agentgate-redis`, and `agentgate-keycloak` healthy.

**What to say:**
> "AgentGate is a modular monolith in `apps/gateway` fronting a protected downstream service in `apps/demo-tools`, backed by PostgreSQL, Redis, and Keycloak. Everything starts with one Docker Compose command and exposes Actuator readiness probes."

---

## 2. Trigger a Pending Mock Refund & Reject in Browser (2:00 – 6:00)

**Exact command to leave a mock refund pending:**
```powershell
python -c "import requests, sys; sys.path.insert(0, 'scripts'); from verify_qa1_security import get_client_token; h = {'Authorization': 'Bearer ' + get_client_token('agent-finance-bot'), 'Accept': 'application/json, text/event-stream'}; r1 = requests.post('http://localhost:8080/mcp', headers=h, json={'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'defence-demo','version':'1'}}}, timeout=10); h.update({'Mcp-Session-Id': r1.headers['Mcp-Session-Id']} if r1.headers.get('Mcp-Session-Id') else {}); r2 = requests.post('http://localhost:8080/mcp', headers=h, json={'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'orders_refund','arguments':{'orderId':'ORD-DEFENCE-01','amount':2500.0}}}, timeout=10); print(r2.text)"
```

**Expected output:**
`REQUIRE_APPROVAL: Call held for human approval [ApprovalId: appr-..., Rule: High Value Refund Approval]`

**Browser step (keep credentials off screen):**
1. Open `http://localhost:8080/approvals` in a browser (sign in beforehand or off-screen if sharing).
2. Authenticate at Keycloak as `human-approver` using `KEYCLOAK_APPROVER_PASSWORD` from `.env`. (`human-admin` is intentionally denied `403` on approval actions to enforce separation of duties.)
3. Locate `ORD-DEFENCE-01` (`2500.0`, `agent-finance-bot`, status `PENDING`), click **Reject**, and confirm the status updates to `REJECTED`.

**What to say:**
> "Refunds above 2,000 BDT are high-risk. Instead of reaching `demo-tools`, the pipeline pauses the call in a `PENDING` approval state and notifies the approval dashboard and webhook. Only a `human-approver` can approve, edit, or reject it; `human-admin` is blocked by RBAC."

---

## 3. Audit Integrity ADR Walkthrough (6:00 – 10:00)

Walk through [ADR-0003: Commit audit events to a PostgreSQL hash chain](../architecture/adr/0003-audit-integrity.md):
- **Context:** FR7 and QA3 require explainable calls, write-intent persistence before dispatch, and tamper detection.
- **Options:** Plain application logs, asynchronous/eventual audit queue, or synchronous PostgreSQL SHA-256 hash chain with serialized chain-head updates.
- **Decision:** Use `AuditRecorder` + `JdbcAuditRepository` with a SHA-256 hash chain (`previous_hash` -> `entry_hash`) and `AuditVerificationService`.
- **Consequences & Measured Evidence:**
  - Lookup speed: **0.051 s** in hosted CI (`45949032689`) and **0.087 s** in local rehearsal (target `< 60 s`).
  - Tamper detection: `AuditRecorderIntegrationTest#tamperDetection_*` (3 tests) catches row edits, middle deletions, and tail deletions.
  - Trade-off: Serializing on the chain head caused **314.22 ms p95** audit lock wait at 100 calls/sec in the original QA2 run, which is why QA2 missed the 30 ms target (423.45 ms p95 in hosted k6).

*(Optional verification command if asked: `./mvnw.cmd -pl apps/gateway "-Dtest=AuditRecorderIntegrationTest#tamperDetection_*" test`.)*

---

## 4. Instructor Live Change (10:00 – 15:00)

Keep 5 minutes for the instructor's on-the-spot change. Actual extension points and targeted tests in the repository:
- **PII Redaction Guard:** [PiiRedactionGuard.java](../../apps/gateway/src/main/java/com/agentgate/gateway/guard/PiiRedactionGuard.java) — verify with `./mvnw.cmd -pl apps/gateway "-Dtest=PiiRedactionGuardTest" test`
- **Schema Validation Guard:** [JsonSchemaValidationGuard.java](../../apps/gateway/src/main/java/com/agentgate/gateway/guard/JsonSchemaValidationGuard.java) — verify with `./mvnw.cmd -pl apps/gateway "-Dtest=JsonSchemaValidationGuardTest" test`
- **Response Truncation Guard:** [ResponseTruncationGuard.java](../../apps/gateway/src/main/java/com/agentgate/gateway/guard/ResponseTruncationGuard.java) — verify with `./mvnw.cmd -pl apps/gateway "-Dtest=ResponseTruncationGuardTest" test`
- **Policy Evaluation:** [StructuredPolicyEvaluator.java](../../apps/gateway/src/main/java/com/agentgate/gateway/policy/service/StructuredPolicyEvaluator.java) — verify with `./mvnw.cmd -pl apps/gateway "-Dtest=PolicyEngineTest" test`

---

## Optional Segment: Delegated Order Allow & Deny (`verify_delegated_orders.py`)

Keep `python scripts/verify_delegated_orders.py` as an **optional** segment until it exits `0` cleanly on this laptop:
- **Hosted CI (`3c5fad4`, run `45949032689`):** Passed end-to-end.
- **Local laptop (`a0018fa`, 2026-11-27):** Passed agent-only discovery, QA3 lookup (`0.087s`), own-order allow (`SUCCESS`, `pol-read-orders`), cross-customer deny (`BLOCKED`, `DEFAULT_DENY`), and live policy v1/v2 updates (`0.10s` / `0.12s`), then **exited nonzero** on the final `[POLICY_DRY_RUN]` `docker compose logs` assertion.

---

## Rehearsal Record & Manual Checklist (2026-11-27)

- **Automated / API checks run:**
  - `docker compose up -d --wait`: all 5 containers healthy.
  - `curl.exe -s http://localhost:8080/actuator/health/readiness` & `http://localhost:8082/actuator/health/readiness`: both returned `{"status":"UP"}`.
  - `orders_refund` (`ORD-REHEARSAL-01`, `2500.0`) MCP call: returned `REQUIRE_APPROVAL` (`appr-630c4fc4-fa5e-41b5-afd9-b4cd877b8347`).
  - API rejection rehearsal: `POST /api/v1/approvals/appr-630c4fc4-fa5e-41b5-afd9-b4cd877b8347/reject` returned HTTP `403` for `human-admin` and HTTP `200 REJECTED` for `human-approver`.
  - `python scripts/verify_delegated_orders.py`: demonstrated allow/deny and live policy updates, exited nonzero on the final `[POLICY_DRY_RUN]` log assertion.
- **Manual browser rehearsal still needed before live defence:**
  - Sign in at `http://localhost:8080/approvals` as `human-approver` (`KEYCLOAK_APPROVER_PASSWORD`, off screen), locate a pending `orders_refund` request, click **Reject**, and confirm its status updates to `REJECTED` in the browser UI.
