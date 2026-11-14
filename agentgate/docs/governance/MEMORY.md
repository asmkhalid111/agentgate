# Project Memory — factual handoff state

Updated: 2026-11-15 (initial planning state)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: M00 APPROVED & COMPLETE (Approved by ChatGPT on 2026-11-15).
- Last approved milestone: M00 (Architecture Baseline & Feasibility).
- Current milestone in progress: M01 IN PLANNING (Application Foundation & Secure Skeleton).
- Production code: NONE. Governance pack and M00 approved plan only. Code remains unwritten and unverified.
- Known checkpoint change request: NOT ISSUED.
- Ratified baseline: Java 21 LTS, Spring Boot 4.0.9, Spring AI 2.0.2 (via `spring-ai-bom`), official starters `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client`, springdoc 3.0.x, Keycloak 26.x, PostgreSQL 16, Redis 7, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose (<=2.5GB container memory budget).

## Implementation cautions recorded for future milestones
1. **M01 (MCP Endpoint Property):** Verify `spring.ai.mcp.server.streamable-http.mcp-endpoint` as the exact MCP endpoint configuration property.
2. **M01/M02/M05 (Write Idempotency):** Require stable client-provided idempotency keys (`operationId`) for write operations; do not rely on server-generated keys when a response may be lost.
3. **M06 (Redis Reservation Expiration):** Define safe Redis reservation expiration (TTL) and release behavior.
4. **M07 (Audit WAL Event Replay):** Ensure audit WAL replay handles distinct intent (`DISPATCHED`) and outcome (`COMPLETED`/`FAILED`) events.
5. **General Pipeline Execution (Per-Call Authorization):** Enforce tool authorization on every invocation, not merely at MCP session initialization.

## Open decisions / risks for M01
- Dependency compilation and context load verification (M01 Spike 1).
- Spring AI MCP Streamable HTTP protocol negotiation and per-identity dynamic tool listing assessment (M01 Spike 2).
- Single-command Docker Compose resource verification on 8GB host laptop.

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
