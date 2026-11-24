# Project Memory — factual handoff state

Updated: 2026-11-19 (Day 1 Implementation Complete, Awaiting Reviewer Gate)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: **M04 (VERIFICATION, AUDIT & QA ACCEPTANCE)** — Task 16F-B Executed (Awaiting External Reviewer Gate).
- Active Reports:
  - [docs/quality/performance/QA2_POOL_SIZING_EXPERIMENT.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/QA2_POOL_SIZING_EXPERIMENT.md) (Task 16F-B Empirical Validation)
  - [docs/quality/performance/QA2_OPTIMIZATION_DECISION.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/QA2_OPTIMIZATION_DECISION.md) (ADR-005 Optimization Architecture Decision)
  - [docs/quality/performance/QA2_BOTTLENECK_ANALYSIS.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/QA2_BOTTLENECK_ANALYSIS.md) (Task 16E Diagnostic Profiling + Errata)
- Recent Commits:
  - `5fa1aa3`: QA1 security acceptance test suite (Task 15b).
  - `d52b2c8`: QA2 performance benchmark harness implementation (Task 16b).
  - `54ed1bc`: QA2 verified rerun report & fix (Task 16d).
  - `b4a4c08`: Root-cause performance profiling and bottleneck analysis (Task 16e).
- Test status:
  - Automated Tests: **295/295 passed** (`.\mvnw.cmd test`), 0 failures, 0 errors.
  - ArchUnit Architecture Test: **Passed** (zero cyclic dependencies across all packages).
  - QA1 Security Acceptance: **38/38 malicious calls blocked/held, 200/200 legitimate calls permitted (0% FP)**.
  - QA2 Performance Optimization (Task 16F-B):
    - HikariCP pool expanded to 30 connections (`maximum-pool-size: 30`, `minimum-idle: 15`).
    - Audit lock wait p95: **$281.37\text{ ms} \to 2.50\text{ ms}$ (-99.1%)**.
    - Permission check p95: **$98.76\text{ ms} \to 2.00\text{ ms}$ (-98.0%)**.
    - Audit commit p95: **$73.21\text{ ms} \to 3.09\text{ ms}$ (-95.8%)**.
    - Total gateway overhead p95: **$447.71\text{ ms} \to 58.78\text{ ms}$ (-86.9%)**.
    - Delivered throughput at 100 RPS: **99.84 RPS**.
    - Audit chain integrity: **13,502 / 13,502 records verified valid**.
    - Primary remaining factor: Security ingress at $48.02\text{ ms}$ ($81.7\%$ of total overhead).
- Docker stack: All 5 normal development containers healthy (`agentgate-gateway`, `agentgate-demo-tools`, `agentgate-postgres`, `agentgate-redis`, `agentgate-keycloak`). Benchmark stack cleanly torn down.

## Implementation cautions & findings recorded for future milestones / days
1. **Day 2 (Pluggable Guard Pipeline):** Implement schema validation, clearance-based PII redaction, and oversize truncation in `pipeline.guard`.
2. **Day 2 (Human Approval State Machine):** 5-state model (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`) with Redis state storage and TTL expiration.
3. **Day 2 (Safety Controls & Kill Switches):** Atomic write caps and rate limits via Redis scripts; global and per-agent emergency kill switches.
4. **Day 3 (Audit Durability):** Minimal durable PostgreSQL audit chain with persistent append-only fallback journal for DB outages (Reviewer Amendment 2).
5. **General Pipeline Execution (Per-Call Authorization):** Enforce tool authorization on every invocation, not merely at MCP session initialization (verified in Day 1).

## Next Delivery Step
- **Reviewer Inspection Gate for M02 (Day 1)** — Halt and await external reviewer approval of [docs/milestones/M02/COMPLETION.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M02/COMPLETION.md) before proceeding to M03 (Day 2: Guards, Approvals & Safety Controls).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
