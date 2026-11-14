# Errors and Risks Register

Do not confuse **anticipated risks** with **observed defects**. C11 requires at least three real examples where AI output was wrong/incomplete and a demonstrated fix. Anticipated entries do NOT count.

## Anticipated risks (not errors yet)
| ID | Risk | Preventive test | Target phase |
|---|---|---|---|
| R01 | Wrong Spring Boot/Spring AI/springdoc dependency combination | Reproducible Maven resolve + app start | M00/M01 |
| R02 | Unsecured MCP `tools/list` / `tools/call` due to starter defaults | Anonymous and cross-agent integration tests | M01/M02 |
| R03 | Dynamic tool list not filtered per requesting agent | Two-agent list contrast | M02 |
| R04 | Caller spoofs user identity for cross-customer reads | Signed/bound context tests | M03 |
| R05 | Policy hot reload conflicts with cache or fail-safe timeout | Clock-controlled tests | M03 |
| R06 | Approval edit bypasses schema/policy re-evaluation | Edited-argument tests | M05 |
| R07 | Limit counters race under concurrent calls | Burst/load test | M06 |
| R08 | Kill switch waits for policy-cache TTL or leaves pending requests live | Disable-in-flight tests | M06 |
| R09 | Audit hash chain breaks under concurrent writes or leaks PII | Concurrent verifier + privacy fixture | M07 |
| R10 | OpenAPI importer allows arbitrary SSRF destinations | Allowlist and malformed-spec tests | M02 |
| R11 | Docker Compose exceeds 8GB available RAM | Measured 8GB laptop run | M01/M10 |
| R12 | Gateway overhead exceeds 30ms p95 at 100 calls/sec | Direct vs gateway k6 bench | M08/M10 |

## Observed defects (actual occurrences only)
| Error ID | Milestone/time | Reproduction | Root cause | Corrective change | Regression evidence | AI error? |
|---|---|---|---|---|---|---|
| ERR-001 | M00 / 2026-11-15 | Review of `docs/milestones/M00/PLAN.md` draft | AI proposed Spring Boot 3.x alternatives despite course mandate for Boot 4.x | Removed Boot 3.x, locked baseline to Spring Boot 4.0.9 & Spring AI 2.0.2 | `docs/milestones/M00/PLAN.md` Section 2 | Yes (AI-1) |
| ERR-002 | M00 / 2026-11-15 | Architecture review of QA4 outage flow | AI assumed synchronous PostgreSQL auditing during PostgreSQL outage | Designed local WAL buffer (`audit-deferred.wal`) with post-outage continuous reconciliation | `docs/milestones/M00/PLAN.md` Section 6 | Yes (AI-2) |
| ERR-003 | M00 / 2026-11-15 | Architecture review of Redis failure | AI proposed in-memory rate limiting fallback during Redis outage | Gated in-memory to dev/test; write tools fail closed on Redis outage; pre-dispatch checks | `docs/milestones/M00/PLAN.md` Section 7 | Yes (AI-3) |
| ERR-004 | M00 / 2026-11-15 | Review of M00 Revision 1 MCP coordinates | AI used incorrect starter names and misattributed Streamable HTTP to 2024-11-05 spec | Used `spring-ai-starter-mcp-server-webmvc` via BOM; corrected Streamable HTTP to 2025-03-26 spec | `docs/milestones/M00/PLAN.md` Section 2 & 4 | Yes (AI-4) |
| ERR-005 | M00 / 2026-11-15 | Review of M00 Revision 1 idempotency | AI derived operation identity solely from agent, tool, and argument hashes | Required explicit client-supplied `operationId` to allow repeated identical legitimate operations | `docs/milestones/M00/PLAN.md` Section 6 | Yes (AI-5) |
| ERR-006 | M00 / 2026-11-15 | Review of M00 Revision 1 user delegation | AI allowed signed user JWT without verifying token was minted for calling agent | Required `azp` claim in user assertion matching calling agent's `clientId` | `docs/milestones/M00/PLAN.md` Section 9 | Yes (AI-6) |
| ERR-007 | M00 / 2026-11-15 | Review of M00 Revision 1 approval state machine | AI introduced undeclared intermediate states (`APPROVED_WITH_EDITS`, etc.) | Defined authoritative 5-state machine (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`) | `docs/milestones/M00/PLAN.md` Section 7 | Yes (AI-7) |

## Reporting template
```
Error ID:
Observed at commit:
Expected / actual:
Steps to reproduce:
Error log or test output path:
Root cause (verified, not guessed):
Fix and modified files:
Rerun command and result:
AI output wrong/incomplete? If yes, link AI_USAGE_LOG.md:
```
