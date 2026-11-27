# Project Memory — factual handoff state

Updated: 2026-11-27 (Final Local Engineering Verification Complete on `85f8a5b`)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. Instructor handout: `CSE444_Course_Projects.docx` (authoritative).

## Current state
- **Branch & Baseline:** `master` (resumed from `a0018fa`; tested CI revision `3c5fad4` on `origin/review/agentgate-ci`).
- **Active Reports & Runbook:**
  - [M12 Completion](../milestones/M12/COMPLETION.md) (Private hosted CI verification, run `45949032689`)
  - [Quality Evidence Report](../quality/QA_EVIDENCE_REPORT.md) (Consolidated QA1–QA5 evidence)
  - [Defence Rehearsal](../defence/DEFENCE_REHEARSAL.md) (15-minute C12 live-defence runbook)
  - [Requirements Matrix](REQUIREMENTS_MATRIX.md) (Reconciled 27-requirement matrix)
- **Test & CI Status:**
  - **Local Full Verify (`85f8a5b`, `.\mvnw.cmd -B verify`):** **314 passed** (0 failures, 0 errors, 0 skips): `gateway` Surefire **310** (including 6/6 ArchUnit checks in `ModularMonolithArchitectureTest`), `gateway` Failsafe **2** (`ContainerStorageIT` + `PolicyStoreOutagePostgresIT`), `demo-tools` **2**.
  - **Hosted CI (`3c5fad4`, run `45949032689`):** **308 passed**, QA1 **38/38** malicious blocked/held and **0/200** false positives, QA3 **0.051 s** lookup, QA5 automated rehearsal **0.36 s**.
  - QA2 Performance (**Deferred**): **423.45 ms p95**, **873 dropped iterations** at 100 calls/sec (target `<= 30 ms p95`); excluded from work plan.
  - QA4 Fail-safe (**Verified for policy-read outage**): Both `PolicyStoreOutageIntegrationTest` (1/1) and `PolicyStoreOutagePostgresIT` (1/1 on `postgres:16-alpine` + Flyway + unmocked `PolicyRepository`) verify med/high fail closed before dispatch, low-risk cached at 299s, cut off at 300s/301s, kill switch & 16/16 audit verified; full shared-PostgreSQL outage intentionally fails closed to preserve FR7/FR8.
  - QA5 Adoption (**Pending**): Automated rehearsal passed in 0.36 s hosted (8.40 s local); independent developer trial pending.
  - C6 Architecture Tests (**Verified**): `ModularMonolithArchitectureTest` passes 6/6 ArchUnit rules.
- **Completion Count:** **22 of 27 (81%)** under the policy-read-outage interpretation of QA4 (FR1–FR10, QA1, QA3, QA4, C1–C8, C11), with **5 open/deferred/waiting** (QA2 deferred, QA5 pending independent participant, C9 pending QA5, C10 awaiting instructor, C12 prepared / live defence pending). *(21 of 27 / 78% if full shared-DB outage were required for QA4.)*
- **Local Docker Stack (Smoke-checked 2026-11-27):** `docker compose up -d --wait` reported all 5 services healthy; `/actuator/health/readiness` returned `{"status":"UP"}` on both `gateway` (8080) and `demo-tools` (8082).

## Next Action
- **Manually rehearse the `/approvals` browser flow** (`KEYCLOAK_APPROVER_PASSWORD` off screen) and **deliver the 15-minute C12 live defence** using [Defence Rehearsal](../defence/DEFENCE_REHEARSAL.md) (or execute **C10** once the instructor issues a checkpoint change request, or run **QA5** once an independent developer is available).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
