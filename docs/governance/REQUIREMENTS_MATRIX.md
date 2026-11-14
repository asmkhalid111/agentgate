# Project 1 Requirements Traceability Matrix

**Source:** Uploaded `CSE444_Course_Projects(1).docx`, Project 1 (pages 1-2) and common requirements (page 8). This matrix is a planning aid, not a replacement for the instructor's exact wording. Update Status/Evidence after every milestone. Planned != implemented != verified.

Statuses: NOT STARTED | PLANNED | IMPLEMENTED | VERIFIED | BLOCKED.

## Functional requirements
| ID | Requirement paraphrase | Primary milestone | Acceptance proof | Status |
|---|---|---|---|---|
| FR1 | Register MCP/REST tools with metadata/risk/schema/owner; OpenAPI import; one authorized MCP listing | M02 | Create/import tool, authenticated filtered `tools/list` and `tools/call` | NOT STARTED |
| FR2 | Registered OAuth2 client-credentials identity; optional end-user context | M01/M03 | Valid/invalid client tokens, cross-user spoof attempt | NOT STARTED |
| FR3 | Default-deny rules on agent/tool/arguments, versioned, <=10s rollout, dry run | M03 | Decision matrix, restart-free update timer, unchanged enforcement in dry-run | NOT STARTED |
| FR4 | Pending approvals for risky calls; page/webhook; approve/reject/edit; timeout; clear agent result | M05 | State/timeout/edit/notification/idempotency tests | NOT STARTED |
| FR5 | Input schema validation, clearance-based email/BD-phone/NID redaction, oversized-result notice | M04 | Bad schema blocked, redaction and truncation fixtures | NOT STARTED |
| FR6 | Per-agent/tool rate + daily quota + hourly write cap; alert breaches | M06 | Boundary/reset/concurrency/alert tests | NOT STARTED |
| FR7 | Full redacted audit, rule and approval provenance, hash chaining, search/export | M07 | Audit fixture, tamper mutation + verify, search/export | NOT STARTED |
| FR8 | Instant kill agent/tool/all write tools, cancel pending | M06 | Concurrent disable/approval cancellation / no follow-on execution | NOT STARTED |
| FR9 | Policy playground for current/draft decisions with matched rule | M03 | Sample-call preview matches runtime decision | NOT STARTED |
| FR10 | Tool timeout/circuit breaker; unhealthy hidden until recovery | M08 | Down/up simulation, list visibility and recovery | NOT STARTED |

## Quality targets
| ID | Exact measure | Milestone | Evidence needed | Status |
|---|---|---|---|---|
| QA1 | 30 malicious: 100% blocked/held; 200 legitimate: <=5% false blocks | M09 | Input vectors, raw results and summary | NOT STARTED |
| QA2 | <=30ms gateway overhead p95 at 100 calls/sec, approvals excluded | M10 | k6/gatling raw data, baseline direct vs gateway, machine/load notes | NOT STARTED |
| QA3 | Audit query <1 min; any stored-row edit caught by verify | M07/M10 | Stopwatch walkthrough and tamper test | NOT STARTED |
| QA4 | Policy-store outage: medium/high fail closed; low last known <=5m | M03/M10 | Time-controlled outage test with logs | NOT STARTED |
| QA5 | New developer adds REST tool in <10min with zero code edits | M09/M10 | Timed walkthrough, tutorial, screenshots/log | NOT STARTED |

## Common requirements
| ID | Requirement paraphrase | Milestone(s) | Evidence | Status |
|---|---|---|---|---|
| C1 | Java21/25, Boot4, Compose one command, 8GB RAM, free services | M01,M10 | `docker compose up --build -d`, memory measurements | NOT STARTED |
| C2 | Profiles/env, validated typed properties, no Git secrets | M01 | Config/test, secret scan | NOT STARTED |
| C3 | springdoc OpenAPI, endpoint security, validation, RFC9457, pagination | M01-M09 | API contracts and tests | NOT STARTED |
| C4 | Actuator, Micrometer metrics/tracing, trace ID in JSON logs | M01,M08 | Runtime trace/log examples | NOT STARTED |
| C5 | JUnit5/slice, Testcontainers, QA executable scripts, CI runs all | M01-M10 | CI jobs, raw scripts/results | NOT STARTED |
| C6 | ArchUnit or Modulith architecture enforcement | M01,M10 | Automatic architecture test | NOT STARTED |
| C7 | C4 context/container/component, 4 ADRs w measured evidence, >=6 scenario utility tree + sensitivity/tradeoffs | M00-M10 | diagrams, ADRs, mini-ATAM | NOT STARTED |
| C8 | Pattern table maps pattern to requirement/classes/rejected option | M00-M10 | Pattern justification table | NOT STARTED |
| C9 | Report all QAs, raw data + charts, failed runs and remediation | M09,M10 | evidence files / charts | NOT STARTED |
| C10 | Implement instructor checkpoint change and report core vs added files | M-CR | issued request and delta note | NOT STARTED |
| C11 | Incremental Git history and AI log with >=3 genuine error/fix cases | All | `git log`, AI log | NOT STARTED |
| C12 | 15-minute ADR walk-through and live small change | M10 | rehearsal checklist | NOT STARTED |

No item may be marked VERIFIED without a link to a reproducible test or evidence file.
