# Project Memory — factual handoff state

Updated: 2026-11-15 (M01 Implementation Complete, Awaiting Reviewer Gate)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: **M01 IMPLEMENTATION COMPLETE** (Awaiting Reviewer Sign-Off Gate).
- Last approved milestone: M00 (Architecture Baseline & Feasibility).
- Current milestone: M01 (Environment and Secure Application Foundation).
- Production code: Aggregator pom.xml, `apps/gateway` (Spring Boot 4.0.9, Spring AI 2.0.2, Security 7.x, Flyway baseline schema, MCP Streamable HTTP), `apps/demo-tools` (mock orders service), Docker Compose multi-service infrastructure.
- Test status: 10/10 automated tests pass (`mvn clean test`), 7/7 multi-container smoke tests pass (`scripts/smoke-test.ps1`).
- Total Container RAM: ~1,006.6 MiB ($\le 2.5\text{ GB}$ laptop memory budget satisfied).
- Known checkpoint change request: NOT ISSUED.
- Ratified baseline: Java 21 LTS, Spring Boot 4.0.9, Spring AI 2.0.2 (via `spring-ai-bom`), official starters `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client`, springdoc 3.0.x, Keycloak 26.x, PostgreSQL 16, Redis 7, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose (<=2.5GB container memory budget).

## Implementation cautions & findings recorded for future milestones
1. **M01/M02 (Dynamic Tool Listing Locus):** Spring AI 2.0.2 publishes registered tools globally during `tools/list`; invocation authorization is strictly enforced at `tools/call`. In M02, dynamic registry filtering per agent identity should wrap tool resolution or intercept `tools/list` JSON-RPC.
2. **M02/M05 (Write Idempotency):** Require stable client-provided idempotency keys (`operationId`) for write operations; do not rely on server-generated keys when a response may be lost.
3. **M06 (Redis Reservation Expiration):** Define safe Redis reservation expiration (TTL) and release behavior.
4. **M07 (Audit WAL Event Replay):** Ensure audit WAL replay handles distinct intent (`DISPATCHED`) and outcome (`COMPLETED`/`FAILED`) events.
5. **General Pipeline Execution (Per-Call Authorization):** Enforce tool authorization on every invocation, not merely at MCP session initialization.

## Next Milestone
- Milestone M02: Dynamic Tool Registry & Metadata Model (Awaiting M01 Reviewer Approval before planning/implementation).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
