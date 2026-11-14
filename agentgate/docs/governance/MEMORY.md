# Project Memory — factual handoff state

Updated: 2026-11-15 (initial planning state)

## Immutable scope
- Project: CSE 444 Project 1 — AgentGate; common requirements C1–C12 apply.
- Goal: meet course requirements with simplest defensible design; postpone cosmetic enhancements.
- Human student: owns decisions and verifies evidence. ChatGPT: senior architecture reviewer. Antigravity: plan/code/test/report executor, gated by review.
- Source file uploaded: `CSE444_Course_Projects(1).docx` (not automatically included in repository).

## Current state
- Milestone: M00 NOT STARTED.
- Last approved milestone: NONE.
- Production code: NONE. A governance pack exists only.
- Known checkpoint change request: NOT ISSUED.
- Proposed, **not yet ratified** architecture: Java21, Boot4, Maven, Spring MVC+virtual threads, Spring AI MCP, PostgreSQL, Keycloak, Redis, Flyway, Thymeleaf, Resilience4j, Micrometer, Docker Compose.

## Open decisions / risks
- Exact tested dependency versions and MCP protocol behavior (including per-agent `tools/list` filtering).
- Single enforceable execution pipeline and end-user trust model.
- Policy-cache <=10s activation + <=5min low-risk-only outage semantics.
- Immediate kill-switch consistency vs cached decisions.
- Rate limit persistence, audit hash chain concurrency, DB-outage audit fallback, 100 calls/sec overhead.
- Compose memory allocation on an 8GB laptop.

## Handoff contract
After an *approved* milestone update this page with: last approved milestone, commit ID, test results summary, architecture decisions, open blockers and next milestone. Link to `docs/milestones/Mxx/COMPLETION.md` and evidence rather than pasting long reports. Never overwrite a failed test record.
