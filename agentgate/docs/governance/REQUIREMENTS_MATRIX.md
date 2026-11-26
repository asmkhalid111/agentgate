# Project 1 Requirements Traceability Matrix

**Source:** Uploaded `CSE444_Course_Projects(1).docx`, Project 1 (pages 1-2) and common requirements (page 8). This matrix is a planning aid, not a replacement for the instructor's exact wording. Update Status/Evidence after every milestone. Planned != implemented != verified.

Statuses: NOT STARTED | PLANNED | IMPLEMENTED | VERIFIED | BLOCKED.

## Functional requirements
| ID | Requirement paraphrase | Primary milestone / Delivery Day | Acceptance proof | Status |
|---|---|---|---|---|
| FR1 | Register MCP/REST tools with metadata/risk/schema/owner; OpenAPI import; one authorized MCP listing | Day 1 (Tool Registry) | Create/import tool, authenticated filtered `tools/list` and `tools/call` | VERIFIED |
| FR2 | Registered OAuth2 client-credentials identity; optional end-user context | Day 1 (JWT Hardening) | [Live delegated order and policy verification](../milestones/M06/COMPLETION.md) | VERIFIED for live demo path |
| FR3 | Default-deny rules on agent/tool/arguments, versioned, <=10s rollout, dry run | Day 1 (Policy Engine) | [Live delegated order and policy verification](../milestones/M06/COMPLETION.md) | VERIFIED for live demo path |
| FR4 | Pending approvals for risky calls; page/webhook; approve/reject/edit; timeout; clear agent result | Day 2 (Approvals) | State/timeout/edit/notification/idempotency tests, `/admin/approvals` UI | PLANNED (Day 2) |
| FR5 | Input schema validation, clearance-based email/BD-phone/NID redaction, oversized-result notice | Day 2 (Guards) | Bad schema blocked, redaction regex fixtures and truncation notice | PLANNED (Day 2) |
| FR6 | Per-agent/tool rate + daily quota + hourly write cap; alert breaches | Day 2 (Limits) | Boundary/reset/concurrency/alert tests via Redis token bucket | PLANNED (Day 2) |
| FR7 | Full redacted audit, rule and approval provenance, hash chaining, search/export | Day 3 (Audit WAL) | Audit fixture, SHA-256 tamper mutation + verify, search/export JSON/CSV | PLANNED (Day 3) |
| FR8 | Instant kill agent/tool/all write tools, cancel pending | Day 2 (Kill Switches) | Concurrent disable/approval cancellation / no follow-on execution | PLANNED (Day 2) |
| FR9 | Policy playground for current/draft decisions with matched rule | Day 1 (Playground) | Sample-call preview matches runtime decision with rule ID | PLANNED (Day 1) |
| FR10 | Tool timeout/circuit breaker; unhealthy hidden until recovery | Day 3 (Resilience) | Down/up simulation, dynamic list visibility and recovery | PLANNED (Day 3) |

## Quality targets
| ID | Exact measure | Milestone / Delivery Day | Evidence needed | Status |
|---|---|---|---|---|
| QA1 | 30 malicious: 100% blocked/held; 200 legitimate: <=5% false blocks | Day 3 | Red-team script `scripts/qa/qa1_security_suite.py` results | PLANNED (Day 3) |
| QA2 | <=30ms gateway overhead p95 at 100 calls/sec, approvals excluded | Day 3 | Benchmark script `scripts/qa/qa2_performance_benchmark.py` | PLANNED (Day 3) |
| QA3 | Audit query <1 min; any stored-row edit caught by verify | Day 3 | [Timed lookup and tamper checks](../quality/QA3_AUDITABILITY.md) | VERIFIED on local stack |
| QA4 | Policy-store outage: medium/high fail closed; low last known <=5m | Day 1 | Time-controlled outage test with logs in `PolicyOutageFailSafeTest` | PLANNED (Day 1) |
| QA5 | New developer adds REST tool in <10min with zero code edits | Day 3 | Timed walkthrough guide `docs/adoption/QA5_WALKTHROUGH.md` | PLANNED (Day 3) |

## Common requirements
| ID | Requirement paraphrase | Milestone(s) / Delivery Day | Evidence | Status |
|---|---|---|---|---|
| C1 | Java 21, Spring Boot 4, Maven, one-command Compose startup on 8 GB RAM, free services | M09 | [Startup verification](../milestones/M09/COMPLETION.md) | OWNER ACCEPTED (physical 8 GB host unverified) |
| C2 | Profiles and environment, validated typed properties, no repository secrets | M10 | [Fresh local provisioning and runtime verification](../milestones/M10/COMPLETION.md) | VERIFIED for current files |
| C3 | OpenAPI, security, validation, RFC 9457 and pagination | M05 / M11 | [Gateway and demo verification](../milestones/M11/COMPLETION.md) | VERIFIED locally |
| C4 | Actuator, Micrometer metrics/tracing, trace ID in JSON logs | C3/C4 follow-up | Actuator health/readiness and metrics plus matching gateway/demo-tools JSON trace IDs on REST and MCP calls | VERIFIED |
| C5 | JUnit 5 unit and slice tests, Testcontainers, runnable QA scripts, CI | C5 checkpoint | [Local verification and CI status](../milestones/M07/COMPLETION.md) | PARTIALLY VERIFIED (hosted CI pending) |
| C6 | ArchUnit or Modulith architecture enforcement | Baseline & Day 1–3 | `ModularMonolithArchitectureTest` passes with zero cyclic dependencies | PARTIALLY IMPLEMENTED (Baseline in M01) |
| C7 | C4 diagrams, four evidence-backed ADRs, and utility tree with sensitivity and trade-offs | M08 | [Diagram, ADR and utility-tree evidence](../milestones/M08/COMPLETION.md) | COMPLETE |
| C8 | Implemented patterns mapped to requirements, participating classes and rejected options | M08 | [Pattern justification](../architecture/PATTERN_MAP.md) | COMPLETE |
| C9 | Results, raw data, charts, failed runs and remediation for every quality attribute | M08 | [Consolidated quality evidence report](../quality/QA_EVIDENCE_REPORT.md) | PARTIALLY VERIFIED (independent QA5 result pending) |
| C10 | Implement instructor checkpoint change and report core vs added files | M-CR | Standby for instructor-issued request and delta note | STANDBY |
| C11 | Incremental Git history and AI log with >=3 genuine error/fix cases | All | `git log`, AI log (12 AI errors tracked and remediated) | IN PROGRESS |
| C12 | 15-minute ADR walk-through and live small change | Day 3 | Defense checklist in `docs/defence/DEFENCE_REHEARSAL.md` | PLANNED (Day 3) |

No item may be marked VERIFIED without a link to a reproducible test or evidence file.
