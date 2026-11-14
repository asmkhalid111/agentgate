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
| _none_ | | | | | | |

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
