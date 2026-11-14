# AgentGate — start here

This is a **project-governance starter pack**, not a compiled Spring Boot application. Do not claim the system works before M01 produces runnable code and passing tests.

Scope: **CSE 444 Project 1 — AgentGate** plus **C1–C12 common requirements only**. No other project from the course brief is in scope.

## Setup
1. Extract this directory as the Git repository root and open **this root folder** in Antigravity.
2. Confirm Antigravity discovers root `AGENTS.md` and the skills under `.agents/skills/` (Customizations panel). See Google Antigravity docs if needed.
3. Inspect `docs/governance/SOURCE_REQUIREMENTS.md`, `docs/governance/ROADMAP.md`, `REQUIREMENTS_MATRIX.md`, `MEMORY.md` and `ERRORS.md`.
4. Run `git init` if needed, then commit the starter governance documents as the first small commit.
5. In Antigravity paste `prompts/00-MASTER-INITIALIZATION.md` (or its content). Request a plan for **M00 only**. **Do not authorize writing application code yet.**
6. Send M00 implementation plan back to the architecture reviewer (ChatGPT). Get approval before implementation.
7. After approval use `prompts/02-IMPLEMENT-APPROVED.md`. Ask Antigravity to provide a completion report with verifiable commands/results; send the report for review.

## Dependency/version suggestions (verify during M00)
- Java 21, Maven Wrapper, Spring Boot 4.0.9, Spring AI 2.0.2, springdoc compatible 3.0.x (verify exact versions).
- Spring MVC + virtual threads, PostgreSQL, Flyway, Keycloak, Redis, Resilience4j, Thymeleaf, Micrometer, JUnit 5, Testcontainers, ArchUnit, k6.
- Spring AI MCP Streamable HTTP and MCP client. Verify whether dynamic per-identity tool listing and execution can be secured with chosen Spring AI version; do not assume starter defaults are safe.
- Docker Compose must be one-command runnable on an 8GB-RAM laptop, with no paid APIs.

## Team and AI process
Human student owns code, tests, and defense. Antigravity plans/implements/reports. ChatGPT reviews architecture and evidence. A report never replaces a reproducible test.

## Source of truth
The instructor's Project 1 and C1–C12 text outranks this starter pack. Any conflict must be surfaced, not silently resolved. The instructor's later checkpoint change request must be inserted as M-CR.
