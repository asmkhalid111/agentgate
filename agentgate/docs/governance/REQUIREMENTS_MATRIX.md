# Project 1 Requirements Traceability Matrix

**Source:** Uploaded `CSE444_Course_Projects.docx`, Project 1 (pages 1–2) and common requirements (page 8). This matrix is a planning aid, not a replacement for the instructor's exact wording. Update Status/Evidence after every milestone. Planned != implemented != verified.

Statuses: NOT STARTED | PLANNED | IMPLEMENTED | PARTIAL | VERIFIED | COMPLETE | FAIL | PENDING | STANDBY.

## Functional requirements (10 / 10 verified)
| ID | Requirement paraphrase | Primary milestone / Delivery Day | Acceptance proof | Status |
|---|---|---|---|---|
| FR1 | Register MCP/REST tools with metadata/risk/schema/owner; OpenAPI import; one authorized MCP listing | Day 1 / M11 / M12 | `ToolRegistryServiceTest`, `McpDiscoveryFilteringIntegrationTest`, `McpDownstreamIntegrationTest`, [M11](../milestones/M11/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR2 | Registered OAuth2 client-credentials identity; optional end-user context | Day 1 / M06 / M12 | `JwtSecurityIntegrationTest`, `DelegationFilterTest`, [M06](../milestones/M06/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR3 | Default-deny rules on agent/tool/arguments, versioned, <=10s rollout, dry run | Day 1 / M06 / M12 | `PolicyEngineTest`, [M06](../milestones/M06/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR4 | Pending approvals for risky calls; page/webhook; approve/reject/edit; timeout; clear agent result | Day 2 / M11 / M12 | `ApprovalControllerTest`, `ApprovalIntegrationTest`, `ApprovalWebhookIntegrationTest`, `ApprovalWebInterfaceTest`, `ApprovedToolExecutionTest`, [M11](../milestones/M11/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR5 | Input schema validation, clearance-based email/BD-phone/NID redaction, oversized-result notice | Day 2 / M04 / M12 | `JsonSchemaValidationGuardTest`, `PiiRedactionGuardTest`, `ResponseTruncationGuardTest`, [QA1 report](../milestones/M04/QA1_SECURITY_REPORT.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR6 | Per-agent/tool rate + daily quota + hourly write cap; alert breaches | Day 2 / M07 / M12 | `RateLimiterPipelineIntegrationTest`, `RedisRateLimiterIntegrationTest`, `RedisWriteCapLimiterIntegrationTest`, [M07](../milestones/M07/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR7 | Full redacted audit, rule and approval provenance, hash chaining, search/export | Day 3 / M07 / M12 | `AuditAdminControllerTest`, `AuditRecorderIntegrationTest`, `AuditTransactionRollbackTest`, `CsvExportUtilTest`, [QA3](../quality/QA3_AUDITABILITY.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR8 | Instant kill agent/tool/all write tools, cancel pending | Day 2 / M12 | `KillSwitchAdminControllerTest`, `KillSwitchIntegrationTest`, [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR9 | Policy playground for current/draft decisions with matched rule | Day 1 / M04 / M12 | `PolicyPlaygroundControllerTest`, [QA1 report](../milestones/M04/QA1_SECURITY_REPORT.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| FR10 | Tool timeout/circuit breaker; unhealthy hidden until recovery | Day 3 / M07 / M12 | `ToolCircuitBreakerTest`, `ToolExecutionTimeoutTest`, `ToolResiliencePipelineIntegrationTest`, `McpResilienceDiscoveryTest`, [M12](../milestones/M12/COMPLETION.md) | VERIFIED |

## Quality targets (3 / 5 verified, 1 failed/deferred, 1 pending)
| ID | Exact measure | Milestone / Delivery Day | Evidence needed | Status |
|---|---|---|---|---|
| QA1 | 30 malicious: 100% blocked/held; 200 legitimate: <=5% false blocks | M04 / M12 | 38/38 malicious blocked/held, 0/200 false blocks in [QA1 report](../milestones/M04/QA1_SECURITY_REPORT.md) and [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| QA2 | At most 30 ms p95 gateway overhead at 100 calls/sec, excluding approval waits | M04 / M07 / M12 | [Original acceptance](../quality/performance/QA2_FINAL_ACCEPTANCE.md) and [hosted k6 results](../milestones/M12/COMPLETION.md) | FAIL (Deferred — 423.45 ms p95 and 873 dropped iterations on latest hosted run) |
| QA3 | Audit query <1 min; any stored-row edit caught by verify | Day 3 / M12 | [QA3 auditability](../quality/QA3_AUDITABILITY.md) (0.332 s local, 0.051 s hosted) and `AuditRecorderIntegrationTest#tamperDetection_*` | VERIFIED |
| QA4 | Policy-store outage: medium/high fail closed; low last known <=5m | Day 1 / M12 | [PolicyStoreOutageIntegrationTest](../../apps/gateway/src/test/java/com/agentgate/gateway/policy/PolicyStoreOutageIntegrationTest.java#L218-L282) (H2 spy, 1/1 passed), [PolicyStoreOutagePostgresIT](../../apps/gateway/src/test/java/com/agentgate/gateway/policy/PolicyStoreOutagePostgresIT.java) (real `postgres:16-alpine` + Flyway + unmocked `PolicyRepository`, 1/1 passed in `.\mvnw.cmd -B verify`: med/high fail closed, low cached at 299s, cut off at 300s/301s, kill switch & 16/16 audit verified) and [QA4 verification](../quality/QA4_POLICY_STORE_OUTAGE_VERIFICATION.md) | VERIFIED (policy-read outage; full shared-PostgreSQL outage fails closed to preserve FR7 audit durability and FR8 kill-switch safety) |
| QA5 | New developer adds REST tool in <10min with zero code edits | Day 3 / M12 | Automated rehearsal (0.36 s hosted, 8.40 s local) in [M12](../milestones/M12/COMPLETION.md); [walkthrough](../adoption/QA5_WALKTHROUGH.md) | PENDING (Independent developer trial awaiting external participant) |

## Common requirements (9 / 12 complete/verified, 0 partial, 1 standby, 2 pending)
| ID | Requirement paraphrase | Milestone(s) / Delivery Day | Evidence | Status |
|---|---|---|---|---|
| C1 | Java 21, Spring Boot 4, Maven, one-command Compose startup on 8 GB RAM, free services | M09 / M10 | [Startup verification](../milestones/M09/COMPLETION.md) | OWNER ACCEPTED (physical 8 GB host unverified) |
| C2 | Profiles and environment, validated typed properties, no repository secrets | M10 | [Fresh local provisioning and runtime verification](../milestones/M10/COMPLETION.md) | VERIFIED for current files |
| C3 | OpenAPI, security, validation, RFC 9457 and pagination | M05 / M11 / M12 | [Gateway and demo verification](../milestones/M11/COMPLETION.md), [M12](../milestones/M12/COMPLETION.md) | VERIFIED |
| C4 | Actuator, Micrometer metrics/tracing, trace ID in JSON logs | M05 | [C3/C4 checkpoint](../milestones/M05/COMPLETION.md), [Live evidence](../milestones/M05/C4_LIVE_EVIDENCE.txt) | VERIFIED |
| C5 | Unit and slice tests, Testcontainers, runnable QA checks, and CI | M07 / M12 | [Hosted verification and uploaded reports](../milestones/M12/COMPLETION.md) (308 Maven tests, run `45949032689`) | VERIFIED hosted execution |
| C6 | ArchUnit or Modulith architecture enforcement | Baseline (M01) / C6 | [ModularMonolithArchitectureTest](../../apps/gateway/src/test/java/com/agentgate/gateway/architecture/ModularMonolithArchitectureTest.java) passes 6 ArchUnit rules (6/6, 0 failures) enforcing acyclic feature modules, `..pipeline..` / `ToolAdapter` isolation, no `..controller..` -> `..pipeline..` or JDBC bypass, `..repository..` isolation, and `..guard..` isolation | VERIFIED |
| C7 | C4 diagrams, four evidence-backed ADRs, and utility tree with sensitivity and trade-offs | M08 | [Diagram, ADR and utility-tree evidence](../milestones/M08/COMPLETION.md) | COMPLETE |
| C8 | Implemented patterns mapped to requirements, participating classes and rejected options | M08 | [Pattern justification](../architecture/PATTERN_MAP.md) | COMPLETE |
| C9 | Results, raw data, charts, failed runs and remediation for every quality attribute | M08 / M12 | [Consolidated quality evidence report](../quality/QA_EVIDENCE_REPORT.md) | PARTIAL (Independent QA5 result pending) |
| C10 | Implement instructor checkpoint change and report core vs added files | M-CR | Standby for instructor-issued request and delta note | STANDBY |
| C11 | Incremental Git history and AI log with >=3 genuine error/fix cases | All | 66+ commits in `git log`, [AI usage log](AI_USAGE_LOG.md) (11+ corrections), [Errors register](ERRORS.md) (12 AI errors tracked) | COMPLETE (Active) |
| C12 | 15-minute ADR walk-through and live small change | Day 3 / C12 | Rehearsed runbook in [Defence rehearsal](../defence/DEFENCE_REHEARSAL.md) | PREPARED (Live defence pending) |

## Completion summary
- **Complete / verified / owner-accepted:** **22 of 27 (81%)** under the policy-read-outage interpretation of QA4 — FR1–FR10 (10), QA1 (1), QA3 (1), QA4 (1), C1–C8 (8), C11 (1). *(Under a strict shared-PostgreSQL full-outage interpretation, QA4 would remain partial and the count would be 21 of 27 / 78%.)*
- **Open / deferred / waiting on a person (5 of 27):** QA2 (failed, deferred), QA5 (pending independent developer), C9 (pending QA5), C10 (awaiting instructor), C12 (prepared, live defence pending).

No item may be marked VERIFIED without a link to a reproducible test or evidence file.
