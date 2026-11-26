# AI Usage Log (C11)

Track tools, significant prompt summaries or paths, review outcomes, and actual corrections. Do not manufacture the required three AI mistakes. Record them when they occur.

| Time | AI tool/model (if known) | Milestone | Prompt or artifact | Intended output | Actual outcome | Reviewer response | Commit/evidence |
|---|---|---|---|---|---|---|---|
| 2026-11-15 | ChatGPT | M00 planning | Initial governance and roadmap | Establish milestone control | Governance pack proposed; code not implemented | Pending | N/A |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Draft | `prompts/00-MASTER-INITIALIZATION.md` | Comprehensive M00 implementation plan | Draft produced with 3 design defects (Boot 3.x, DB audit outage trap, Redis fallback security leak) | CHANGES REQUIRED (Review Revision 1) | `docs/milestones/M00/PLAN.md` (initial commit `fae4483`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Rev 1 | Architecture Review Revision 1 | Correct all 10 reviewer mandates | Revised plan with WAL audit, fail-closed Redis, Boot 4.0.9 baseline, REVISION-01.md | CHANGES REQUIRED (Review Revision 2) | `docs/milestones/M00/PLAN.md` (commit `7463760`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M00 Plan Rev 2 | Architecture Review Revision 2 | Final targeted corrections (MCP coordinates, idempotency, azp-bound delegation, 5-state machine, pre-dispatch limits) | Authoritative plan revision, REVISION-02.md | APPROVED (Official Reviewer Verdict 2026-11-15) | `docs/milestones/M00/PLAN.md` (commit `24d1922`) |
| 2026-11-15 | Antigravity (Gemini 3.8 Flash) | M01 Implementation | M01 Approved Plan Execution | Working Java 21, Spring Boot 4.0.9, Docker Compose stack, Keycloak 26, MCP Streamable HTTP | All automated tests passing (10/10), all smoke tests passing (7/7), 1.0 GB RAM footprint | Awaiting External Reviewer Verification | `docs/milestones/M01/COMPLETION.md` |

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
- **Original AI Suggestion:** In M00 Revision 1, the AI introduced undeclared intermediate states (`APPROVED_WITH_EDITS`, `EXPIRED_IN_FLIGHT`).
- **What was Incorrect/Incomplete:** Introducing undeclared states caused contradictory lifecycle logic and violated the finite state machine contract.
- **Review Evidence that Exposed it:** Architecture Review Revision 2, Item 3: "Provide one authoritative state-transition table. Remove contradictory or undeclared states."
- **Change Made & Re-verification:** Formally unified all approval states in `PLAN.md` Section 7 to exactly 5 authoritative states: `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`. Edits are recorded as transition metadata without creating a new state.

### AI Correction 8 (Linked to ERR-008)
- **Original AI Suggestion:** In M01 initial implementation, the AI used `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` which failed to compile under Spring Boot 4.x.
- **What was Incorrect/Incomplete:** Spring Boot 4 modularized test auto-configuration starters into distinct modules (`spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`).
- **Review Evidence that Exposed it:** Maven build failure: `package org.springframework.boot.test.autoconfigure.web.servlet does not exist`.
- **Change Made & Re-verification:** Added `spring-boot-starter-webmvc-test` to `apps/gateway/pom.xml` and imported `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Build and tests passed cleanly.

### AI Correction 9 (Linked to ERR-009)
- **Original AI Suggestion:** In M01 initial implementation, the AI omitted test profile fallback for `JwtDecoder`, assuming the remote Keycloak JWKS endpoint would be mocked via MockMvc.
- **What was Incorrect/Incomplete:** Spring Security OAuth2 resource server refuses application startup with `NoSuchBeanDefinitionException` if neither an issuer URI nor a `JwtDecoder` bean is available.
- **Review Evidence that Exposed it:** Test context failure: `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.security.oauth2.jwt.JwtDecoder'`.
- **Change Made & Re-verification:** Configured a `@ConditionalOnMissingBean` fallback decoder in `SecurityConfig.java` providing valid claims for unit/slice tests when no live IdP is present.

### AI Correction 10 (Linked to ERR-010)
- **Original AI Suggestion:** In M01 Docker Compose setup, the AI assumed the Gateway could validate tokens against `KEYCLOAK_ISSUER_URI=http://keycloak:8080/realms/agentgate`.
- **What was Incorrect/Incomplete:** Keycloak issues JWT tokens with external canonical issuer `http://localhost:8180/realms/agentgate` (per `KC_HOSTNAME_URL`). Validating against `keycloak:8080` fails issuer validation; disabling issuer validation violates reviewer mandate 1.A.
- **Review Evidence that Exposed it:** Reviewer condition 1.A: "Implement a consistent Keycloak issuer configuration... JWT issuer validation and cryptographic signature verification must remain enabled."
- **Change Made & Re-verification:** Configured `SecurityConfig.java` to set internal `jwk-set-uri=http://keycloak:8080/...` while enforcing canonical issuer check `JwtValidators.createDefaultWithIssuer("http://localhost:8180/realms/agentgate")`. Both signature and issuer validation succeeded across container and host clients.

### AI Correction 11 (Linked to ERR-011)
- **Original AI Suggestion:** In M01 initial implementation, the AI annotated `DemoToolsMcpProvider` with `@Component` and assumed Spring AI MCP server would automatically discover and register its `@Tool` methods.
- **What was Incorrect/Incomplete:** In Spring AI 2.0.2, tools must be explicitly exposed to the MCP server specification via a `ToolCallbackProvider` bean. Without it, `McpServerAutoConfiguration` reports `Registered tools: 0` and `tools/list` returns empty.
- **Review Evidence that Exposed it:** MCP client spike returned `{"jsonrpc":"2.0","id":11,"result":{"tools":[]}}` and tool calls failed with `Unknown tool`.
- **Change Made & Re-verification:** Created `apps/gateway/src/main/java/com/agentgate/gateway/config/McpConfig.java` declaring `@Bean public ToolCallbackProvider demoToolsCallbackProvider(DemoToolsMcpProvider p) { return ToolCallbackProvider.from(ToolCallbacks.from(p)); }`. Gateway log confirmed `Registered tools: 2` and discovery test passed.

## Codex C3/C4 follow-up

The user requested an FR1 baseline check followed by an approval-gated C3/C4 pass. Codex initially missed a direct test caller of the registry controller and omitted the Brave runtime bridge from the first build. A clean compile and live trace check exposed these gaps. The test caller and application dependencies were corrected. See the [completion report](../milestones/M05/COMPLETION.md).
