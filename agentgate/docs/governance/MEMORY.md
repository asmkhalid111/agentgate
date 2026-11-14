# Project Memory — factual handoff state

Updated: 2026-11-15 (initial planning state)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: M00 IN REVIEW (Revision 1 submitted).
- Last approved milestone: NONE.
- Production code: NONE. Governance pack and M00 plan revision only.
- Known checkpoint change request: NOT ISSUED.
- Proposed, **revised baseline awaiting approval**: Java 21 LTS, Spring Boot 4.0.9, Spring AI 2.0.2, springdoc 3.0.x, Keycloak 26.x, PostgreSQL 16, Redis 7, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose (<=2.5GB container memory budget).

## Open decisions / risks
- Dependency compilation and context load verification in M01 (Spike 1).
- Spring AI MCP protocol negotiation and dynamic per-identity `tools/list` filtering in M01 (Spike 2).
- Final reviewer ratification of M00 Revision 1 (Local WAL audit fallback, Redis fail-closed write semantics, Signed User Assertion JWTs).

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
