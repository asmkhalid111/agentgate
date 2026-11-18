# Roadmap — gated implementation

> **Current Execution Strategy:** See the [Approved Three-Day Delivery Plan](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/plans/THREE_DAY_DELIVERY_PLAN.md) (v1.1.0), which organizes delivery into a streamlined 5-milestone structure (`M00`–`M04`):
> - **M00:** Architecture Baseline ([M00 Completion](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M00/COMPLETION.md)) — **APPROVED**
> - **M01:** Environment & Secure Foundation ([M01 Completion](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M01/COMPLETION.md)) — **APPROVED**
> - **M02:** Day 1 Core Gateway ([M02 Completion](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M02/COMPLETION.md)) — **IMPLEMENTED** (Awaiting Reviewer Sign-Off)
> - **M03:** Day 2 Governance Controls (Guards, Approvals, Limits, Kill Switches) — Scheduled
> - **M04:** Day 3 Verification, Audit & Submission (Audit Trail, Resilience, QA1–QA5, CI, Live Defence) — Scheduled

Rules: one milestone at a time; plan -> architecture review -> approval -> implement -> reproducible tests -> completion report -> review. `M-CR` is inserted after the instructor issues the change request; do not invent it.

| ID | Milestone | Deliverable / exit gate | Coverage |
|---|---|---|---|
| M00 | Scope, baseline, technical feasibility | Requirements traceability, architecture proposal, ADR candidates, dependency/protocol compatibility plan, local prerequisite inventory. Reviewer approves architecture. | C1,C7,C8,C11 |
| M01 | Environment and secure skeleton | Maven/Boot 4 + Docker Compose, PostgreSQL, Keycloak OAuth2, Redis if approved, demo-tools, authenticated MCP proof, CI smoke, configs/health. Entire system starts with one command. | FR2 (base); C1-C6 (base) |
| M02 | Registry and adapter pipeline | Register MCP and REST downstream tools, import OpenAPI, risk/owner/schema, filtered `tools/list`, permitted `tools/call`, governed invocation. | FR1, FR2 |
| M03 | Policies + playground | Default deny; agent, user and argument rules; policy version, hot reload <=10 s; dry run; draft preview; fail-safe last-known policies. | FR3,FR9,QA4 |
| M04 | Schema and response guards | Input JSON Schema checks, redact e-mail/BD phone/NID by clearance, oversize result notice; pluggable guards. | FR5 |
| M05 | Human approvals | Persistent states, request page + webhook, approve/reject/edit, timeout rejection, clear client outcomes; concurrency and idempotency tests. | FR4 |
| M06 | Limits + emergency switches | Per-agent, per-tool rates/daily quotas; write cap; breach alerts; disable agent/tool/all writes; cancel pending. | FR6,FR8 |
| M07 | Audit & verification | Search/export audit with agent/user/tool/redacted args/rule/approval/latency/outcome; hash chain; verify command detects modification. | FR7,QA3 |
| M08 | Resilience and downstream health | Per-tool timeouts, circuit breakers, hide unhealthy tools and restore on recovery; metrics/trace correlation. | FR10 |
| M09 | E2E and security acceptance | Minimal admin workflows, scripted MCP clients, mock tools, 30 malicious and 200 legitimate cases; adoption rehearsal. | All FR; QA1,QA5 |
| M10 | Evidence and live defence | Repeatable benchmark (100 cps overhead), audit/reliability/adoption measurements, raw outputs/charts, C4, 4+ evidence-based ADRs, utility tree, pattern table, CI evidence, demo defence. | All QA; C1-C12 |
| M-CR | Instructor change request (timing TBD) | Change implemented after issued, record core file touches vs new components, tests and ADR delta. | C10 |

## QA acceptance targets (DO NOT lower)
- QA1: 30/30 malicious calls blocked or held; <=10/200 legitimate calls wrongly blocked.
- QA2: <=30 ms p95 gateway-added overhead at 100 calls/second, excluding approval wait.
- QA3: find call explanation in <1 minute; manual audit-row edit detected by verifier.
- QA4: unreachable policy store -> medium/high fail closed; low can continue using last known policies for <=5 minutes.
- QA5: unfamiliar classmate imports/configures a REST tool + risk + policy with no code change in <10 minutes.

## Performance/correctness early warnings
Run probes ahead of final phase for MCP security, list filtering, policy freshness, audit chain concurrency, approval timeouts, 8GB memory, and 100 calls/sec performance. Do not claim the targets pass until measured.
