# Project Memory — factual handoff state

Updated: 2026-11-27 (QA4 Real-PostgreSQL Outage Test Verified)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. Instructor handout: `CSE444_Course_Projects.docx` (authoritative).

## Current state
- **Branch & Baseline:** `master` (resumed from `a0018fa`; tested CI revision `3c5fad4` on `origin/review/agentgate-ci`).
- **Active Reports & Runbook:**
  - [docs/milestones/M12/COMPLETION.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M12/COMPLETION.md) (Hosted CI verification, run `45949032689`)
  - [docs/quality/QA_EVIDENCE_REPORT.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/QA_EVIDENCE_REPORT.md) (Consolidated QA1–QA5 evidence)
  - [docs/defence/DEFENCE_REHEARSAL.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/defence/DEFENCE_REHEARSAL.md) (15-minute C12 live-defence runbook)
  - [docs/governance/REQUIREMENTS_MATRIX.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/governance/REQUIREMENTS_MATRIX.md) (Reconciled 27-requirement matrix)
- **Test & CI Status (Hosted CI `3c5fad4`, run `45949032689`):**
  - Automated Maven Tests: **308 passed**, 0 failures, 0 errors, 0 skips (unit, slice, Testcontainers Postgres/Redis, ArchUnit cycle check, QA3 tamper, QA4 outage).
  - QA1 Security: **38/38 malicious blocked or held, 0/200 legitimate falsely blocked**.
  - QA2 Performance (**Deferred**): **423.45 ms p95**, **873 dropped iterations** at 100 calls/sec (target `<= 30 ms p95`); excluded from work plan.
  - QA3 Auditability: **0.051 s** hosted lookup (`0.087 s` local rehearsal); 3/3 tamper checks pass.
  - QA4 Fail-safe (**Partial**): Both `PolicyStoreOutageIntegrationTest` (1/1 passed) and `PolicyStoreOutagePostgresIT` (`.\mvnw.cmd -pl apps/gateway "-Dtest=NonExistentTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=PolicyStoreOutagePostgresIT" verify`, 1/1 passed on `postgres:16-alpine` + Flyway + unmocked `PolicyRepository`) verify policy-read outage fallback (med/high fail closed before dispatch, low-risk cached at 299s, cut off at 300s/301s, kill switch & 16/16 audit verified); full shared-PostgreSQL outage intentionally fails closed to preserve FR7/FR8.
  - QA5 Adoption (**Pending**): Automated rehearsal passed in 0.36 s hosted (8.40 s local); independent developer trial pending.
  - C6 Architecture Tests (**Verified**): `ModularMonolithArchitectureTest` passes 6/6 ArchUnit rules (`.\mvnw.cmd -pl apps/gateway "-Dtest=ModularMonolithArchitectureTest" test`, 0 failures): acyclic feature modules, `ToolAdapter` isolation in `..pipeline..`, no `..controller..` -> `..pipeline..` or JDBC bypass, `..repository..` isolation, and `..guard..` isolation.
- **Completion Count:** **21 of 27 (78%)** strictly complete/owner-accepted (FR1–FR10, QA1, QA3, C1–C8, C11), plus **1 partial** (QA4) and **5 open/deferred/waiting** (QA2, QA5, C9, C10, C12).
- **Local Docker Stack (Rehearsed 2026-11-27):** All 5 services healthy (`agentgate-gateway`, `agentgate-demo-tools`, `agentgate-postgres`, `agentgate-redis`, `agentgate-keycloak`). Readiness `UP`, pending `orders_refund` + API `human-approver` rejection verified; `verify_delegated_orders.py` passed discovery, QA3, allow/deny, and live policy v1/v2 updates locally, exiting nonzero on the final `[POLICY_DRY_RUN]` log assertion (kept optional in runbook; passed in hosted CI `45949032689`). Browser UI sign-in and rejection at `/approvals` remain a manual rehearsal item.

## Next Action
- **Manually rehearse the `/approvals` browser flow** (`KEYCLOAK_APPROVER_PASSWORD` off screen) and **deliver the 15-minute C12 live defence** using [docs/defence/DEFENCE_REHEARSAL.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/defence/DEFENCE_REHEARSAL.md) (or execute **C10** once the instructor issues a checkpoint change request, or run **QA5** once an independent developer is available).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
