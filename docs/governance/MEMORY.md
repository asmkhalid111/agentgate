# Project Memory — factual handoff state

Updated: 2026-11-15 (M01 Implementation Complete, Awaiting Reviewer Gate)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: **M01 REVIEW COMPLETE -> THREE-DAY DELIVERY PLAN PROPOSED** (Awaiting Reviewer Sign-Off Gate).
- Last approved milestone: M00 (Architecture Baseline & Feasibility).
- Current milestone / plan: [Revised Three-Day Delivery Plan](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/plans/THREE_DAY_DELIVERY_PLAN.md).
- Production code: Aggregator pom.xml, `apps/gateway` (Spring Boot 4.0.9, Spring AI 2.0.2, Security 7.x, Flyway baseline schema, MCP Streamable HTTP), `apps/demo-tools` (mock orders service), Docker Compose multi-service infrastructure.
- Test status: 10/10 automated tests pass (`mvn clean test`), 7/7 multi-container smoke tests pass (`scripts/smoke-test.ps1`).
- Total Container RAM: ~1,006.6 MiB ($\le 2.5\text{ GB}$ laptop memory budget satisfied).
- Known checkpoint change request: NOT ISSUED.
- Ratified baseline: Java 21 LTS, Spring Boot 4.0.9, Spring AI 2.0.2 (via `spring-ai-bom`), official starters `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client`, springdoc 3.0.x, Keycloak 26.x, PostgreSQL 16, Redis 7, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose (<=2.5GB container memory budget).

## Implementation cautions & findings recorded for future milestones / days
1. **M01 Remediation (JWT Decoder Test Isolation):** Production `SecurityConfig.java` must fail-fast on startup if JWKS/issuer properties are missing; mock decoder must be strictly isolated to `@TestConfiguration` in `src/test/java/`. Scheduled for Day 1 Task 1.0.
2. **M01 Remediation (MCP Protocol Version):** Spring AI MCP Core 2.1.0 supports `2024-11-05`, `2025-03-26`, `2025-06-18`, and `2025-11-25`. Retain working Streamable HTTP integration; accurately record negotiated version.
3. **M01/Day 1 (Dynamic Tool Listing Locus):** Spring AI 2.0.2 publishes registered tools globally during `tools/list`; invocation authorization is strictly enforced at `tools/call`. In Day 1, dynamic registry filtering per agent identity wraps tool resolution.
4. **Day 1/Day 2 (Write Idempotency):** Require stable client-provided idempotency keys (`operationId`) for write operations; do not rely on server-generated keys when a response may be lost.
5. **Day 2 (Redis Reservation Expiration):** Define safe Redis reservation expiration (TTL) and release behavior.
6. **Day 3 (Audit WAL Event Replay):** Ensure audit WAL replay handles distinct intent (`DISPATCHED`) and outcome (`COMPLETED`/`FAILED`) events.
7. **General Pipeline Execution (Per-Call Authorization):** Enforce tool authorization on every invocation, not merely at MCP session initialization.

## Next Delivery Step
- **Day 1 Implementation (Security Hardening & Core Governance Engine)** — Scheduled to start upon external reviewer approval of `docs/plans/THREE_DAY_DELIVERY_PLAN.md`.

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
