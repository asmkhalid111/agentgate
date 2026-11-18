# Project Memory — factual handoff state

Updated: 2026-11-19 (Day 1 Implementation Complete, Awaiting Reviewer Gate)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: **THREE-DAY PLAN — DAY 1 COMPLETE** (Awaiting Reviewer Exit Gate Inspection).
- Last approved plan: Three-Day Delivery Plan v1.1.0 (`cfc111f`).
- Commits on Day 1:
  - `56e4e2b`: JWT security hardening, fail-fast production decoder, and test mock isolation (Task 1.0).
  - `2747e8a`: Dynamic tool registry, OpenAPI importer, dual REST/MCP adapters (Task 1.1).
  - `4e84cc8`: Structured policy engine, thread-safe atomic cache, hot reload, and playground (Tasks 1.2, 1.3).
  - `7b30e1c`: MCP dynamic tool registration listener (`notifyToolsListChanged`), administrative security, Flyway database bootstrap, and end-to-end verification suite.
- Production code: Aggregator pom.xml, `apps/gateway` (Spring Boot 4.0.9, Spring AI 2.0.2, Security 7.x, Flyway V1-V3, MCP Streamable HTTP server, Dynamic Tool Registry, Structured Policy Engine, REST/MCP adapters, Policy Playground), `apps/demo-tools` (mock orders service), Docker Compose multi-service infrastructure.
- Test status:
  - Automated Tests: **27/27 passed** (`mvn test -pl apps/gateway`), 0 failures, 0 errors.
  - ArchUnit Architecture Test: **Passed** (zero cyclic dependencies across all packages).
  - Multi-Container Live Verification (`scripts/verify-day1.py`): **8/8 suites passed (100%)** against live Keycloak 26, PostgreSQL 16, Redis 7, Gateway, and Demo Tools.
  - M01 Regression Smoke Test (`scripts/smoke-test.ps1`): **7/7 suites passed**.
- Total Container RAM: ~1,089 MiB ($\le 2.5\text{ GB}$ laptop memory budget satisfied).
- Known checkpoint change request: NOT ISSUED (C10 on standby).
- Ratified baseline: Java 21 LTS, Spring Boot 4.0.9, Spring AI 2.0.2 (via `spring-ai-bom`), official starters `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client`, springdoc 3.0.x, Keycloak 26.x, PostgreSQL 16, Redis 7, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose (<=2.5GB container memory budget).

## Implementation cautions & findings recorded for future milestones / days
1. **Day 2 (Pluggable Guard Pipeline):** Implement schema validation, clearance-based PII redaction, and oversize truncation in `pipeline.guard`.
2. **Day 2 (Human Approval State Machine):** 5-state model (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`) with Redis state storage and TTL expiration.
3. **Day 2 (Safety Controls & Kill Switches):** Atomic write caps and rate limits via Redis scripts; global and per-agent emergency kill switches.
4. **Day 3 (Audit Durability):** Minimal durable PostgreSQL audit chain with persistent append-only fallback journal for DB outages (Reviewer Amendment 2).
5. **General Pipeline Execution (Per-Call Authorization):** Enforce tool authorization on every invocation, not merely at MCP session initialization (verified in Day 1).

## Next Delivery Step
- **Reviewer Inspection Gate for Day 1** — Halt and await external reviewer approval of Day 1 deliverables before proceeding to Day 2 (Guards, Approvals & Safety Controls).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
