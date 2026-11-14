# AgentGate Agent Instructions — always active

## Mission and source
Implement only **CSE 444 Project 1: AgentGate** plus common requirements C1–C12. Functional requirements FR1–FR10 and quality attributes QA1–QA5 are mandatory. The instructor may later supply a checkpoint change request (M-CR). Do not work on Projects 2–5.

Read, in order, `docs/governance/SOURCE_REQUIREMENTS.md`, `docs/governance/REQUIREMENTS_MATRIX.md`, `ROADMAP.md`, `MEMORY.md`, `ERRORS.md` and the current `docs/milestones/Mxx/PLAN.md` (if present). The original instructor handout remains authoritative if available. Do not silently change its requirements.

## Gated operating mode
1. PLAN: Before touching application code, prepare a scoped milestone plan and stop. State affected files, decisions, acceptance tests, risks and anticipated errors. Ask for explicit reviewer approval.
2. IMPLEMENT: Implement **only a specifically approved plan**. If new fundamental design or dependency is necessary, stop and request reapproval.
3. VERIFY: Run tests, collect raw outputs, inspect diffs and update the requirements matrix. Do not claim tests were run when they were not.
4. REPORT: Write `docs/milestones/Mxx/COMPLETION.md` including implementation detail, files changed, commands/results, failures, evidence paths, commits and next risks. Stop for human/reviewer sign-off.
5. REVIEW: Do not start another milestone until the architecture reviewer returns APPROVED.

## Engineering priorities
1. Correctness and measurable assessment criteria; 2. Security/fail-closed behavior; 3. Understandable modular structure; 4. Reproducibility; 5. Minimal complexity; 6. Cosmetic polish.

## Hard constraints
- Java 21 or 25 and Spring Boot 4.x. Prefer Java 21. Use free tooling. `docker compose up --build -d` must start the whole running system after M01.
- Keep a **modular monolith** in `apps/gateway`, with feature-based packages, and one mock downstream app in `apps/demo-tools`. Changes to this baseline require an ADR and approval.
- Authenticate **all MCP requests** and every applicable API endpoint. Never assume Spring AI MCP starter automatically applies security. Verify authorization on both `tools/list` and `tools/call` for the *current* client identity.
- All tool execution (MCP or REST) must run through a single governed execution orchestrator. No bypass via an adapter, admin endpoint, direct controller method, health check or demo path.
- Default-deny policy. Verify end-user context rather than trusting user IDs supplied by untrusted callers. Never let dry-run silently bypass the mandatory baseline security controls. For high-risk tool calls, require approval.
- Input validation before downstream call; output redaction and truncation before client response; audit arguments and results must not leak sensitive data.
- Store no secrets in Git. Provide `.env.example` with placeholders. Minimize paid/external services.
- Use Flyway migrations, typed configuration properties with validation, RFC 9457 error responses, pagination for lists, secure OpenAPI docs, structured traceable logs.
- Incremental commits, no fabricated tests, benchmarks, proof, or claimed requirement completion. Follow failing tests with fixes and reruns; keep raw outputs including failures.

## Documentation discipline
- Update `docs/governance/MEMORY.md` for factual project state after each accepted milestone; maintain a short, current summary and link to reports rather than duplicating all details.
- Log **observed** errors in `ERRORS.md`, distinct from *anticipated* risks. Include reproduction, root cause, fix and regression test.
- Log significant AI prompts and corrections in `AI_USAGE_LOG.md` (C11: at least 3 actual wrong/incomplete AI outputs and their fixes; never invent examples).
- Keep ADRs (minimum 4), C4 diagrams, mini-ATAM utility tree, pattern justification and quality evidence current with implementation.
- Create feature files in their owned packages, not by implementation-milestone directories. Keep milestone-specific plans and reports under `docs/milestones/Mxx/`.

## Stop conditions
Stop if: task expands beyond approved scope; unknown Spring dependency compatibility; user identity can be spoofed; an MCP call bypasses governance; a performance number is unverified; a test needs external paid services; current code is broken by a change; source requirement is ambiguous. Document the obstacle and offer the simplest viable options.
