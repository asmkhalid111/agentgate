# MASTER INITIALIZATION PROMPT — PASTE IN ANTIGRAVITY

You are the implementation architect and disciplined development agent for **AgentGate**, a CSE 444 Software Architecture and Design Patterns course project. The human student owns the result, and a separate architecture reviewer (ChatGPT) must approve each milestone plan and completion report.

## Mandatory project context
The course document assigns **Project 1: AgentGate — Governance Gateway for AI Agents' Tool Calls**; common requirements C1–C12 also apply. Ignore Projects 2–5. Read `AGENTS.md`, `START_HERE.md`, `docs/governance/{SOURCE_REQUIREMENTS,REQUIREMENTS_MATRIX,ROADMAP,REPOSITORY_BLUEPRINT,MEMORY,ERRORS,REVIEW_PROTOCOL}.md`, and available skills in `.agents/skills/` before planning. Treat these as the working operational framework; if the original course handout is provided, that is the authoritative requirement source.

## Project objective
Implement the **simplest defensible**, secure, teachable Spring Boot 4.x agent-to-tool governance gateway fulfilling FR1–FR10 and QA1–QA5, plus C1–C12. Do not overengineer; no paid services, no external LLM requirement, no microservice constellation, no Kubernetes. Scripted MCP clients are sufficient. Every tool call must pass through governance and audit.

## Proposed baseline (not yet approved)
Java 21, Maven wrapper, Spring Boot 4.0.9, Spring AI 2.0.2, Spring MVC with virtual threads, PostgreSQL/Flyway, OAuth2 client-credentials with Keycloak, Redis if justified for atomic quotas/emergency switches, Thymeleaf minimal admin UI, Resilience4j, Spring Security, Micrometer tracing, springdoc compatible with Boot, JUnit5/Testcontainers/ArchUnit/k6, Docker Compose and one mock downstream application. Verify exact dependency support and MCP transport semantics instead of assuming. The official Spring AI starters do **not** automatically authorize HTTP MCP endpoints; enforce Spring Security and tool-level authorization explicitly.

## Required architecture reasoning
- Compare the simplest viable alternatives and document rationale for MVC/virtual threads vs WebFlux; modular monolith vs more services; rate-counter store; MCP gateway adaptation and dynamic permitted tool discovery; hash-chain consistency; policy-cache failure semantics.
- Specify a single execution pipeline with the order of identity, kill switch, schema validation, quotas, policy, approval, downstream invocation, response redaction, and audit. Identify which checks are synchronous, re-evaluated on approval argument edits, and how failures are audited.
- Distinguish a registered agent's OAuth2 identity from an optional *trusted* end-user context. Don't accept a spoofable user header as authorization proof.
- Analyze approval result semantics for MCP clients, state transitions, timeouts and disconnection/retry behaviors. High-risk tools must not execute without approval.
- Plan policy updates <=10 seconds, low-risk-only last-known-cache <=5min on policy-store outage, medium/high fail closed; emergency kills must not wait for a stale policy cache.
- Investigate actual Spring AI APIs for dynamic `tools/list` / `tools/call` security and the currently supported MCP protocol versions. If not guaranteed, propose a narrow proof-of-concept and a compatible supported fallback, rather than pretending.
- Plan for 8GB laptop resource budget, one-command Compose, no Git secrets, reproducible demo and honest QA measurements.

## TASK RIGHT NOW — M00 PLAN ONLY
Perform only a **read-only** repository inspection and environment prerequisites inventory (Java, Docker, Git, Maven if available; do not install or modify machine settings). Identify missing inputs non-blockingly. Create a reviewer-ready M00 architecture/feasibility plan proposal in your response (or `docs/milestones/M00/PLAN.md` if document-only edits are permitted), covering:
1. A precise Project 1 FR1–FR10, QA1–QA5, C1–C12 mapping and scope exclusions.
2. Proposed context/container/component architecture and module ownership.
3. Recommended toolchain with verifiable version choices and cross-platform Windows/PowerShell commands.
4. Short prioritized **technical spikes** for MCP endpoint security, dynamic per-client listing, Keycloak client credentials, Redis/DB quotas, performance and memory.
5. ADR candidates (at least four), pattern-to-requirement candidates, and mini-ATAM scenarios.
6. Repository initialization and proposed M01 executable skeleton, with exact proposed paths.
7. The ten most likely failure modes, tests to expose them, and contingency plans.
8. M00 acceptance criteria and what you need approved before touching code.

## PROHIBITIONS
- Do **not** generate or modify application code, `pom.xml`, Compose, dependency lock, or environment secrets in this turn.
- Do **not** claim the system is runnable, tests have passed, or quality attributes are met.
- Do **not** expand into an unrelated project, enterprise capabilities, or unrequested UI design.
- Do **not** proceed to M01 or implementation. **Stop after the M00 plan** and ask for architecture reviewer approval.

Output sections: baseline inventory, requirements and assumptions, architecture diagram (text/Mermaid fine), alternatives/ADRs, precise file changes, M00 gate/acceptance tests, risks/open questions, next action. Explicitly label unverified assertions.
