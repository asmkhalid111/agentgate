# AI Usage Log (C11)

Track tools, significant prompt summaries or paths, review outcomes, and actual corrections. Do not manufacture the required three AI mistakes. Record them when they occur.

| Time | AI tool/model (if known) | Milestone | Prompt or artifact | Intended output | Actual outcome | Reviewer response | Commit/evidence |
|---|---|---|---|---|---|---|---|
| 2026-11-15 | ChatGPT | M00 planning | Initial governance and roadmap | Establish milestone control | Governance pack proposed; code not implemented | Pending | N/A |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Draft | `prompts/00-MASTER-INITIALIZATION.md` | Comprehensive M00 implementation plan | Draft produced with 3 design defects (Boot 3.x, DB audit outage trap, Redis fallback security leak) | CHANGES REQUIRED (Review Revision 1) | `docs/milestones/M00/PLAN.md` (initial commit `fae4483`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Rev 1 | Architecture Review Revision 1 | Correct all 10 reviewer mandates | Revised plan with WAL audit, fail-closed Redis, Boot 4.0.9 baseline, REVISION-01.md | CHANGES REQUIRED (Review Revision 2) | `docs/milestones/M00/PLAN.md` (commit `7463760`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Rev 2 | Architecture Review Revision 2 | Final targeted corrections (MCP coordinates, idempotency, azp-bound delegation, 5-state machine, pre-dispatch limits) | Authoritative plan revision, REVISION-02.md | APPROVED (Official Reviewer Verdict 2026-11-15) | `docs/milestones/M00/PLAN.md` (commit `24d1922`) |

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

### AI Correction 4 (Linked to ERR-004)
- **Original AI Suggestion:** In M00 Revision 1, the AI specified `spring-ai-mcp-server-spring-boot-starter` coordinates and misattributed Streamable HTTP transport to the `2024-11-05` MCP specification.
- **What was Incorrect/Incomplete:** The documented Spring AI starter is `spring-ai-starter-mcp-server-webmvc` managed via `spring-ai-bom`. Streamable HTTP was introduced in MCP specification `2025-03-26`, not `2024-11-05`.
- **Review Evidence that Exposed it:** Architecture Review Revision 2, Item 1: "Use the documented Spring AI starter coordinates: `spring-ai-starter-mcp-server-webmvc`... Streamable HTTP was introduced in MCP specification 2025-03-26. Do not label it as a 2024-11-05 transport."
- **Change Made & Re-verification:** Corrected coordinates in `PLAN.md` Section 2 to `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client` via `spring-ai-bom:2.0.2`; corrected historical attribution to `2025-03-26`; acknowledged the `2026-07-28` specification.

### AI Correction 5 (Linked to ERR-005)
- **Original AI Suggestion:** In M00 Revision 1, the AI derived write-tool operation identity solely from `agentId + toolId + SHA-256(arguments)`.
- **What was Incorrect/Incomplete:** Conflating operations by argument hashes prevents legitimate repeated calls with identical parameters (e.g. issuing two identical legitimate 100 BDT payments).
- **Review Evidence that Exposed it:** Architecture Review Revision 2, Item 2: "Never derive operation identity solely from agent ID, tool ID and argument hashes. Two legitimate calls with identical arguments may be separate operations."
- **Change Made & Re-verification:** Redesigned write idempotency in `PLAN.md` Section 6 to require an explicit client-supplied `operationId` (UUIDv4) passed via `_meta.idempotencyKey` or `Idempotency-Key` header, with strict handling for argument mismatch, lost responses, and unconfirmed downstream states.

### AI Correction 6 (Linked to ERR-006)
- **Original AI Suggestion:** In M00 Revision 1, the AI specified that an agent could supply any signed user JWT as an assertion if the agent had the `delegation:act_as_user` scope.
- **What was Incorrect/Incomplete:** A signed user JWT without client binding allows an agent to replay tokens obtained in other contexts without proving the user authorized that specific agent.
- **Review Evidence that Exposed it:** Architecture Review Revision 2, Item 5: "A signed user JWT combined with an agent scope is insufficient unless the assertion is demonstrably authorized for that specific agent."
- **Change Made & Re-verification:** Updated `PLAN.md` Section 9 to require that the signed user assertion token contain an authorized party claim (`azp`) explicitly matching the calling agent's `clientId`, verified against Keycloak JWKS public keys.

### AI Correction 7 (Linked to ERR-007)
- **Original AI Suggestion:** In M00 Revision 1, the AI introduced undeclared intermediate states like `APPROVED_WITH_EDITS`, `CANCELLED_PRE_DISPATCH`, and `DISCONNECTED_PENDING_OUTCOME`.
- **What was Incorrect/Incomplete:** These undeclared states caused ambiguity in the state machine transition table and conflicted with the declared state set.
- **Review Evidence that Exposed it:** Architecture Review Revision 2, Item 3: "Provide one authoritative state-transition table. Remove contradictory or undeclared states... Clarify whether client disconnection cancels the approval or allows it to remain pending."
- **Change Made & Re-verification:** Consolidated the approval state machine in `PLAN.md` Section 7 into strictly 5 authoritative states (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`). Clarified that client disconnection leaves the approval in `PENDING` until the configured timeout.
