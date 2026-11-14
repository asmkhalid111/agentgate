# AI Usage Log (C11)

Track tools, significant prompt summaries or paths, review outcomes, and actual corrections. Do not manufacture the required three AI mistakes. Record them when they occur.

| Time | AI tool/model (if known) | Milestone | Prompt or artifact | Intended output | Actual outcome | Reviewer response | Commit/evidence |
|---|---|---|---|---|---|---|---|
| 2026-11-15 | ChatGPT | M00 planning | Initial governance and roadmap | Establish milestone control | Governance pack proposed; code not implemented | Pending | N/A |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Draft | `prompts/00-MASTER-INITIALIZATION.md` | Comprehensive M00 implementation plan | Draft produced with 3 design defects (Boot 3.x, DB audit outage trap, Redis fallback security leak) | CHANGES REQUIRED (Review Revision 1) | `docs/milestones/M00/PLAN.md` (initial commit `fae4483`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Rev 1 | Architecture Review Revision 1 | Correct all 10 reviewer mandates | Revised plan with WAL audit, fail-closed Redis, Boot 4.0.9 baseline, REVISION-01.md | Awaiting Re-Review | `docs/milestones/M00/PLAN.md` (Revision 1) |

## Required genuine correction examples (at least 3 by delivery)

### AI Correction 1 (Linked to ERR-001)
- **Original AI Suggestion:** In initial M00 draft, the AI included Spring Boot 3.4.x as a fallback implementation alternative alongside Spring Boot 4.x.
- **What was Incorrect/Incomplete:** The course specification explicitly requires Spring Boot 4.x. Offering Boot 3.x as an implementation alternative violated the course baseline requirement.
- **Review Evidence that Exposed it:** Architecture Review Revision 1, Item 2: "The course requires Java 21 or 25 with Spring Boot 4.x... Remove Spring Boot 3.x and old milestone releases as proposed implementation alternatives."
- **Change Made & Re-verification:** Removed all references to Spring Boot 3.x from `PLAN.md`; locked baseline to Spring Boot 4.0.9, Spring AI 2.0.2, and springdoc 3.0.x; designated dependency compatibility as an explicit M01 Spike 1 task.

### AI Correction 2 (Linked to ERR-002)
- **Original AI Suggestion:** In initial M00 draft, the AI specified that during a policy store outage, low-risk tools would continue on cached policies while audit events would continue to be synchronously written to PostgreSQL.
- **What was Incorrect/Incomplete:** If the policy store (PostgreSQL) is unreachable, synchronous PostgreSQL audit inserts will also fail, causing either low-risk calls to crash or audit events to be lost, directly conflicting between QA4 and FR7.
- **Review Evidence that Exposed it:** Architecture Review Revision 1, Item 4: "The current plan uses PostgreSQL for both policies and the audit chain... Do not assume that synchronous PostgreSQL auditing can work during a PostgreSQL outage."
- **Change Made & Re-verification:** Redesigned outage auditing in `PLAN.md` Section 6 to use a local disk Write-Ahead Log (`audit-deferred.wal`) with synchronous `fsync` and local cryptographic sub-chaining, reconciled into PostgreSQL upon recovery. If fallback audit fails, execution fails closed.

### AI Correction 3 (Linked to ERR-003)
- **Original AI Suggestion:** In initial M00 draft, the AI proposed that if Redis failed, the gateway would automatically fall back to an in-memory rate limiter.
- **What was Incorrect/Incomplete:** Automatically falling back to an in-memory rate limiter in production allows uncoordinated concurrent writes and bypasses distributed emergency kill switches, silently weakening production security.
- **Review Evidence that Exposed it:** Architecture Review Revision 1, Item 5: "Redis remains approved for rate limits, quotas and kill-switch state. However, automatic fallback to local memory must not silently weaken production security... When security-critical state is unavailable or untrustworthy, the relevant write operations must fail closed."
- **Change Made & Re-verification:** Updated `PLAN.md` Section 7 to strictly gate in-memory rate limiters behind `@Profile("dev")` / `@Profile("test")`, and enforce that in production, Redis failure causes all write operations and high-risk tools to fail closed immediately (HTTP 503). Added a last-chance pre-dispatch check.
