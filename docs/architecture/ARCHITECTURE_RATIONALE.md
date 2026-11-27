# AgentGate Architecture Rationale

AgentGate gives AI agents one governed entry point for calling tools. The gateway verifies who is calling, decides whether the request can proceed, and records what happened. The architecture was shaped by the need to apply those rules consistently across REST and MCP tools while keeping the project small enough to build, test, and run locally.

### 1. Modular monolith for gateway governance

The gateway is one Spring Boot application, organized by feature. Identity, tool registration, policy, approvals, rate limits, audit, and resilience each have their own responsibilities. Docker Compose runs the gateway alongside Keycloak, PostgreSQL, Redis, and a separate demo tool service.

**Why this choice.** Most governance decisions concern the same request and need to happen in a predictable order. Keeping them in one application makes that flow easier to follow and test. It also lets related operations use a database transaction where needed. ArchUnit checks enforce the project's chosen package and layer boundaries.

**Alternative considered.** Splitting identity, policy, approval, and audit into separate services could let each scale independently. It would also add network calls, more deployment units, and more failure and consistency cases to handle for every governed request.

**Cost.** The gateway scales as a unit. A bottleneck in one part can slow the whole request path. The shared PostgreSQL instance also creates a common failure point. [Component diagrams](C4_DIAGRAMS.md)

### 2. One execution pipeline with REST and MCP adapters

Spring MVC serves the MCP entry point. The governed tool callback passes the request to `ToolExecutionPipeline`, which applies the relevant policy, validation, approval, rate-limit, and audit checks. REST and MCP adapters translate the approved operation into the downstream protocol.

**Why this choice.** A new transport can reuse the same governance rules. For example, a high-risk call can enter an approval state before any downstream request happens, regardless of which adapter would eventually execute it. The pipeline also gives reviewers one place to inspect the ordering of checks.

**Alternatives considered.** Putting the checks inside individual controllers or adapters would duplicate policy logic and make it easier for one protocol to behave differently. Spring WebFlux would offer a different concurrency model, but it would require changes across the request path. The benchmark identified a large audit lock wait, and no WebFlux comparison was measured.

**Cost.** The pipeline is central to the application, so a change to its ordering can affect many features. Blocking calls require timeouts and resource limits. [Transport ADR](adr/0001-mvc-mcp.md) · [Pattern justification](PATTERN_MAP.md)

### 3. Agent identity and user delegation through Keycloak

Agents authenticate with OAuth client credentials. An agent acting on behalf of a user also presents a separate, signed user assertion. The gateway checks the assertion's expiry, audience, delegation scope, and binding to the calling agent. The demo order lookup uses server-controlled ownership data.

**Why this choice.** The gateway can tell which agent made the call and which user authorized it. In the live check, the same agent and verified user could read their own order and were blocked from reading another customer's order. Both outcomes appeared in the audit record.

**Alternatives considered.** Trusting a caller-supplied customer ID in an argument or header would let the caller claim someone else's identity. Accepting any valid user token from the realm would leave the gateway without proof that the token was intended for that agent.

**Cost.** The demo needs a working identity provider, additional configuration, and a consent flow. Every protected resource also needs a trusted way to establish ownership. [Delegation ADR](adr/0002-agent-bound-delegation.md)

### 4. Human approval with a recorded lifecycle

High-risk calls can become pending approvals. The approval service records the request and decision, while execution and notification can react to committed events. The browser demo shows a pending refund and the approver's decision.

**Why this choice.** The system can track a request while it waits for a person, preserve the reason for a decision, and distinguish approval from the outcome of downstream execution. Notifications run separately from the transaction that stores the approval.

**Alternatives considered.** A single approved flag would lose states such as pending, rejected, expired, and execution outcome. Sending the webhook inside the approval transaction would make that transaction wait for the remote receiver.

**Cost.** Asynchronous execution requires careful handling of retries, duplicate work, and state transitions. Tests need to verify the outcome for the specific approval they create. [Pattern justification](PATTERN_MAP.md)

### 5. PostgreSQL audit chain and Redis counters

PostgreSQL stores governance records and transactional audit events. Audited writes record their intent before dispatch. Each audit entry is linked to the previous entry using a SHA-256 hash chain that the verifier can check. Redis supports rate limits, quotas, and write-cap counters.

**Why this choice.** A reviewer can trace a request to its actor, deciding rule, and outcome. The verifier detected an edited row and deleted rows in isolated tests. Redis gives the gateway a way to update counters atomically while PostgreSQL handles durable business records and audit transactions.

**Alternatives considered.** Application logs are useful for diagnosis, while the audit requirement also calls for durable, verifiable records. An asynchronous audit queue could reduce work on the request path, but a queued event alone would not provide the selected guarantee that write intent is persisted before dispatch.

**Cost.** Audit writes serialize at a shared chain head. The earlier acceptance run measured **314.22 ms p95 audit lock wait**. In a separate private hosted CI run (`3c5fad4`), overall gateway overhead reached **423.45 ms p95**, with **873 dropped iterations** at an offered rate of 100 calls per second. The **30 ms p95** QA2 target was missed (failed and deferred). [Audit ADR](adr/0003-audit-integrity.md) · [Quality evidence report](../quality/QA_EVIDENCE_REPORT.md)

### 6. Risk-aware policy fallback and downstream resilience

The gateway evaluates a cached policy snapshot and refreshes it periodically and after administrative changes. During a policy-read failure, medium- and high-risk requests fail closed. A matching low-risk request can use the last known policy for less than five minutes, with the other governance checks still in force. Timeouts and circuit breakers limit repeated downstream failures.

**Why this choice.** It gives permitted low-risk work a short recovery window and keeps stricter control over riskier calls. The PostgreSQL integration test verified low-risk success at 299 seconds, denial at 300 seconds, and continued audit and kill-switch enforcement during the policy-read failure.

**Alternatives considered.** Querying policy storage on every call would tie each decision directly to a live query. Allowing cached decisions for every risk or indefinitely would increase exposure to stale permissions. Denying all calls on the first failed policy read would give up the targeted low-risk recovery window.

**Cost.** Cached policy can briefly lag a change. During a full outage of shared PostgreSQL, registry, audit, and kill-switch dependencies are also affected and the gateway fails closed, including for low-risk calls. [Policy caching ADR](adr/0004-risk-aware-policy-cache.md) · [QA4 verification](../quality/QA4_POLICY_STORE_OUTAGE_VERIFICATION.md)

### What the architecture gives us

AgentGate can explain a complete governance decision from identity through policy, approval, dispatch, and audit. The latest locally verified application revision (`85f8a5b`, documented through `4631aa5`) passed **314 tests**, including six ArchUnit rules and the PostgreSQL policy-read failure test (distinct from the earlier **308-test** private hosted CI run at `3c5fad4`). Its main measured cost is contention in the synchronous audit path. The architecture provides a working, testable governance system with a clear performance limitation to disclose. [Quality evidence report](../quality/QA_EVIDENCE_REPORT.md)
