# AgentGate — Revised Three-Day Delivery Plan
## Minimal Defensible Architecture & Implementation Roadmap (Approved with Targeted Amendments)

**Document Version:** 1.1.0  
**Date:** 2026-11-18  
**Author:** Student Architecture Team (Antigravity Executor)  
**External Reviewer:** Senior Architecture Reviewer (ChatGPT)  
**Status:** **APPROVED WITH TARGETED IMPLEMENTATION CONDITIONS — DAY 1 EXECUTION AUTHORIZED**  
**Project:** CSE 444 Project 1 — AgentGate (Governance Gateway for AI Agents' Tool Calls)  
**Git Baseline Commit:** `061e55d` (`feat(m01): implement environment and secure application foundation`)  
**Target Delivery Window:** 3 Implementation Days  

---

## 1. Executive Summary & Reviewer Context

Following review of `THREE_DAY_DELIVERY_PLAN.md` v1.0.0, the external architecture reviewer issued an **APPROVED WITH TARGETED IMPLEMENTATION CONDITIONS** verdict. The three-day delivery roadmap is confirmed.

This revised plan incorporates the mandatory reviewer amendments:
1. **Structured Policy Conditions:** Simple structured condition evaluator (field paths, comparison operators, and user ownership checks) instead of arbitrary SpEL expressions.
2. **Audit Durability & Fallback Journal:** Durable PostgreSQL audit logging with a local persistent append-only fallback journal for DB outages; if neither durable sink is available, the gateway fails closed before executing tools.
3. **Strict QA4 Semantics:** Medium/high-risk calls fail closed immediately when the policy store is unavailable. Low-risk calls may use valid cached policies for at most 5 minutes.
4. **Dual Tool Support & Invocation Authorization:** Both REST and downstream MCP tools supported through a shared execution pipeline; authorization enforced on both `tools/list` discovery and direct `tools/call` invocations.
5. **Administrative Security:** Protect tool management, policy management, approvals, and kill switches using Keycloak `ROLE_ADMIN` / `ROLE_APPROVER` role checks.
6. **Required Course Evidence:** Focused QA1–QA5 suites, basic CI, C4 diagrams, 4 empirical ADRs, utility tree, pattern table, and honest AI usage logging.
7. **Honest Completion Status:** C10 held for actual instructor change request; C12 marked ready for defense; no invented test results.
8. **Redis & Approval Correctness:** Atomic limits, revalidation of edited arguments, safe expiration/cancellation, and strict kill-switch blocking.

### Scope Philosophy: Minimal Defensible Implementation
1. **Zero Enterprise Bloat:** No unnecessary distributed microservices, complex dynamic rule engines (e.g. Drools), or third-party workflow engines. Everything is implemented as clean, cohesive modules within the ratified Spring Boot modular monolith.
2. **Preserve Successful Foundations:** All working code from M01 (Spring Security OAuth2 Resource Server, Flyway migrations, Demo Tools mock service, Docker Compose configuration, and Python spike client) is preserved intact.
3. **Strict Gatekeeping:** Each day concludes with a demonstrable, reproducible exit gate verified by automated tests and shell/Python scripts. Implementation proceeds strictly day by day.

---

## 2. Integration of M01 Review Remediation Checks

The five essential concerns raised during the M01 completion review are directly addressed and scheduled as foundational activities:

| # | Reviewer Concern | Root Cause / Analysis | Three-Day Plan Resolution & Scheduling |
|---|---|---|---|
| **1** | **JWT Security Hardening** | A `@ConditionalOnMissingBean` fallback `JwtDecoder` was present in production `SecurityConfig.java`, creating a risk that token validation could be bypassed if properties were misconfigured. | **Day 1 (Task 1.0):** Remove the fallback decoder entirely from production `SecurityConfig.java`. Configure strict fail-fast startup behavior if JWKS/issuer properties are missing. Isolate mock `JwtDecoder` strictly to `@TestConfiguration` under `src/test/java/` for slice tests. Verify signature, issuer (`http://localhost:8180/realms/agentgate`), and expiration checks against real Keycloak tokens. |
| **2** | **MCP Compatibility** | The server negotiated protocol `2024-11-05` over Streamable HTTP, whereas Streamable HTTP originated in `2025-03-26`. | **Day 1 (Task 1.0):** Investigation of `io.modelcontextprotocol.sdk:mcp-core:2.1.0` revealed that the server transport supports `2024-11-05`, `2025-03-26`, `2025-06-18`, and `2025-11-25`. The client requested `2024-11-05`, which the server accepted. Retain the working Streamable HTTP integration; accurately document negotiated versions in logs and docs. Interoperability is fully verified with the official Python MCP SDK (`mcp 2.3.0`). |
| **3** | **WSL/Docker Stability** | Containers stopped with status 143 after idle periods due to default WSL2 idle VM termination. | **Retained Baseline:** The container environment is stable. Documented a simple developer runbook note for WSL keepalive. No unauthorized global system modifications will be introduced. |
| **4** | **Requirements Matrix Claims** | M01 completion report marked common requirements C1–C6 as fully "IMPLEMENTED" prematurely before full test suite, CI, and QA benchmarks were built. | **Immediate Update:** Updated `REQUIREMENTS_MATRIX.md` to classify C1–C6 accurately as `PARTIALLY IMPLEMENTED (Baseline in M01)` and clearly trace their full completion to Days 1–3. |
| **5** | **CI & Test Duplication** | Risk of redundant test suites and bloated verification pipelines. | **Day 3 (Task 3.4):** Provide a single, clean GitHub Actions CI workflow (`.github/workflows/ci.yml`) running `mvn clean test` and ArchUnit verification. Use single-purpose Python/PowerShell scripts for QA1, QA2, QA3, and QA5. |

---

## 3. High-Level Three-Day Roadmap

```
+---------------------------------------------------------------------------------------------------+
| DAY 1: SECURITY HARDENING & CORE GOVERNANCE ENGINE                                                |
| - Task 1.0: JWT Security Hardening & Test Isolation (M01 Remediation)                             |
| - Task 1.1: Dynamic Tool Registry & Metadata Model (FR1, FR2)                                      |
| - Task 1.2: Default-Deny Policy Engine, In-Memory Cache & Hot Reload (FR3, QA4)                   |
| - Task 1.3: Policy Playground & Admin APIs (FR9)                                                  |
| Exit Gate: Dynamic tools/list filtered by agent; policy engine evaluates rules in <=10s hot reload |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
| DAY 2: GUARDS, APPROVALS & SAFETY CONTROLS                                                         |
| - Task 2.1: Schema Validation & Response PII Redaction Guard Pipeline (FR5)                       |
| - Task 2.2: Human Approval State Machine & Web/REST Workflow (FR4)                                |
| - Task 2.3: Limits, Blast-Radius Caps & Emergency Kill Switches (FR6, FR8)                         |
| Exit Gate: High-risk calls held; PII redacted; write limits enforced; kill switch halts calls     |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
| DAY 3: AUDIT TRAIL, RESILIENCE, QA ACCEPTANCE & CI                                                |
| - Task 3.1: Tamper-Evident SHA-256 Audit WAL & Verification CLI (FR7, QA3)                        |
| - Task 3.2: Downstream Resilience & Dynamic Health Filtering (FR10)                               |
| - Task 3.3: QA Acceptance Suites: Red Team (QA1), Load Benchmark (QA2), Walkthrough (QA5)         |
| - Task 3.4: GitHub Actions CI, ADR Package & Final Defense Prep (C1-C12)                          |
| Exit Gate: 30/30 attacks blocked; <=30ms p95 overhead; audit tamper detected; full CI passes      |
+---------------------------------------------------------------------------------------------------+
```

---

## 4. Detailed Day-by-Day Implementation Specifications

### Day 1: Security Hardening & Core Governance Engine

**Primary Objectives:** Complete M01 security hardening; implement dynamic tool registry with OpenAPI import; implement default-deny policy engine with hot reload and playground.  
**Requirement Coverage:** M01 Remediation, FR1, FR2, FR3, FR9, QA4, C2, C3, C6.

#### Task 1.0: JWT Security Hardening & Test Isolation (M01 Remediation)
- **Production Hardening (`SecurityConfig.java`):**
  - Delete `@ConditionalOnMissingBean public JwtDecoder jwtDecoder()`.
  - In `jwtDecoderByJwkKeySetUri()`, enforce strict fail-fast validation: if `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` or `issuer-uri` is missing or invalid, throw `IllegalStateException` on application startup.
  - Retain strict `JwtValidators.createDefaultWithIssuer("http://localhost:8180/realms/agentgate")`.
- **Test Isolation (`TestSecurityConfig.java`):**
  - Place a dedicated `@TestConfiguration` under `src/test/java/.../config/` providing a mock `JwtDecoder` bean, active *only* for unit/slice tests where Keycloak is deliberately not started.
- **Verification:**
  - `mvn test -Dtest=JwtSecurityIntegrationTest` passes against running Keycloak container.
  - Startup without Keycloak properties immediately fails fast (fail-safe).

#### Task 1.1: Dynamic Tool Registry & Metadata Model (FR1, FR2)
- **Database Schema (`V2__tool_registry.sql`):**
  ```sql
  CREATE TABLE registered_tools (
      id UUID PRIMARY KEY,
      name VARCHAR(100) UNIQUE NOT NULL,
      description TEXT NOT NULL,
      input_schema JSONB NOT NULL,
      risk_level VARCHAR(20) NOT NULL, -- LOW, MEDIUM, HIGH
      owning_team VARCHAR(100) NOT NULL,
      endpoint_url VARCHAR(500) NOT NULL,
      protocol_type VARCHAR(20) NOT NULL, -- REST, MCP
      enabled BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
  );

  CREATE TABLE tool_agent_permissions (
      tool_id UUID REFERENCES registered_tools(id) ON DELETE CASCADE,
      agent_id VARCHAR(100) NOT NULL,
      PRIMARY KEY (tool_id, agent_id)
  );
  ```
- **Components to Implement (`apps/gateway/src/main/java/com/agentgate/gateway/registry/`):**
  - `RegisteredToolEntity` & `RegisteredToolRepository` (Spring Data JPA).
  - `ToolRegistryService`: CRUD operations, permission mapping.
  - `OpenApiImporterService`: Parses OpenAPI v3 JSON/YAML (using Jackson), extracts endpoints, parameters, and request bodies, maps them into JSON Schema format, and persists them as `RegisteredToolEntity` records.
  - **Shared Execution Pipeline & Dual Tool Adapters:**
    - `ToolExecutionPipeline`: Unified execution abstraction for downstream tools.
    - `RestToolAdapter`: Uses Spring `RestClient` to forward authorized, validated tool arguments to downstream REST endpoints.
    - `McpDownstreamToolAdapter`: Uses Spring AI's `McpClient` / `McpSyncClient` to forward invocations to registered downstream MCP tool servers.
- **Dynamic MCP `tools/list` Filtering & Invocation Authorization:**
  - Wrap the MCP tool provider so that when an agent requests `tools/list` over `/mcp`, the gateway inspects the authenticated agent's OAuth2 client ID (`sub` / `client_id`) and returns *only* the tools registered and permitted for that specific agent.
  - **Strict Direct Invocation Authorization:** When an agent invokes `tools/call`, the gateway verifies agent permissions immediately before pipeline execution, preventing unauthorized direct execution of unlisted tools.

#### Task 1.2: Structured Policy Engine & Hot Reload (FR3, QA4)
- **Database Schema (`V3__policies.sql`):**
  ```sql
  CREATE TABLE policies (
      id UUID PRIMARY KEY,
      name VARCHAR(100) NOT NULL,
      agent_pattern VARCHAR(100) NOT NULL, -- e.g. "agent-finance-*", "*"
      tool_pattern VARCHAR(100) NOT NULL,  -- e.g. "orders_refund", "*"
      conditions_json JSONB,               -- Structured conditions: [{"field": "amount", "op": "LTE", "value": 2000}, {"field": "customerId", "op": "EQUALS_USER_ID"}]
      action VARCHAR(20) NOT NULL,         -- ALLOW, DENY, REQUIRE_APPROVAL
      dry_run BOOLEAN NOT NULL DEFAULT FALSE,
      priority INT NOT NULL DEFAULT 100,
      version INT NOT NULL DEFAULT 1,
      enabled BOOLEAN NOT NULL DEFAULT TRUE,
      updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
  );
  ```
- **Components to Implement (`apps/gateway/src/main/java/com/agentgate/gateway/policy/`):**
  - `PolicyRule` & `PolicyDecision` (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`).
  - `StructuredPolicyEvaluator`: Evaluates structured conditions (field comparisons: `EQ`, `NEQ`, `GT`, `LT`, `GTE`, `LTE`, and ownership check `EQUALS_USER_ID` to prevent cross-customer access) directly on incoming JSON arguments and authenticated user context. No arbitrary script execution.
  - `PolicyRepository` & `PolicyCache`: In-memory thread-safe copy of active rules (`CopyOnWriteArrayList` / immutable list).
  - `PolicyHotReloadService`: Background scheduled task running every 5 seconds (or triggered via REST API event) to reload modified policies from PostgreSQL without restarting the gateway ($\le 10\text{s}$ rollout).
  - **QA4 Fail-Safe Semantics:**
    - If PostgreSQL policy store becomes unreachable:
      - Calls to `MEDIUM` and `HIGH` risk tools **FAIL CLOSED immediately (`DENY`)**.
      - Calls to `LOW` risk tools may continue using valid cached policies for **at most 5 minutes** (strictly cut off after 5 minutes).
  - **Dry-Run Mode:**
    - If a matching rule has `dry_run = true` and would deny or require approval, the engine logs a structured warning (`[POLICY_DRY_RUN] would have blocked call`), but allows execution to proceed downstream.

#### Task 1.3: Policy Playground & Admin Security (FR9, Reviewer Amendment 5)
- **Administrative Security on Management Endpoints:**
  - Secure `/api/v1/registry/**` and `/api/v1/policies/**` with Spring Security requiring Keycloak role `ROLE_ADMIN` (or realm role `admin`).
  - Reject unauthorized management calls with HTTP 403 Forbidden.
- **REST Endpoints (`PolicyPlaygroundController`):**
  - `POST /api/v1/policies/playground`: Accepts sample payload:
    ```json
    {
      "agentId": "agent-finance-bot",
      "toolName": "orders_refund",
      "arguments": { "orderId": "ORD-123", "amount": 2500, "customerId": "usr-99" },
      "userContext": { "userId": "usr-99", "role": "CUSTOMER" },
      "draftRule": null
    }
    ```
    Returns evaluation result:
    ```json
    {
      "decision": "REQUIRE_APPROVAL",
      "matchedRuleId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "matchedRuleName": "High Value Refund Human Approval",
      "dryRun": false,
      "evaluationLatencyMs": 1.2
    }
    ```
  - Standard CRUD endpoints for `/api/v1/registry/tools` and `/api/v1/policies` with Spring Validation, pagination, and RFC 9457 `ProblemDetail` error responses.

#### Day 1 Exit Gate & Demonstration
1. **Automated Unit & Slice Tests:** All M01 security tests pass; `ToolRegistryServiceTest`, `OpenApiImporterTest`, and `PolicyEngineTest` pass.
2. **Scripted Verification (`scripts/verify-day1.ps1` or `.py`):**
   - Start stack via Docker Compose.
   - Import an OpenAPI file via REST endpoint; verify tool appears in registry.
   - Connect via Python MCP client as `agent-support-bot`: verify only support tools are listed in `tools/list`.
   - Call refund tool with amount 1,500 BDT: policy allows call.
   - Call refund tool with amount 3,000 BDT: policy denies call.
   - Update policy via API; execute call within 10 seconds without gateway restart; verify updated policy takes effect immediately.
   - Test Policy Playground endpoint with sample calls; verify matched rule returned.

---

### Day 2: Guards, Approvals & Safety Controls

**Primary Objectives:** Build the pluggable guard pipeline (JSON schema validation, clearance-based PII redaction, oversize truncation); implement the human approval state machine with timeout and Web/REST actions; implement rate limits, write caps, and emergency kill switches.  
**Requirement Coverage:** FR4, FR5, FR6, FR8, C4, C8.

#### Task 2.1: Schema Validation & Response PII Redaction Guard Pipeline (FR5)
- **Guard Architecture (Chain of Responsibility & Strategy Patterns):**
  ```java
  public interface ToolPreExecutionGuard extends Ordered {
      GuardResult inspect(ToolExecutionContext context);
  }

  public interface ToolPostExecutionGuard extends Ordered {
      ToolExecutionResult inspectAndTransform(ToolExecutionContext context, ToolExecutionResult result);
  }
  ```
- **Pre-Execution Guards:**
  - `JsonSchemaValidationGuard` (Order 100): Validates incoming tool arguments against the tool's registered `input_schema` using NetworkNT `json-schema-validator`. Rejects malformed arguments before dispatching with RFC 9457 `ProblemDetail`.
- **Post-Execution Guards:**
  - `PiiRedactionGuard` (Order 200): Inspects string fields in downstream tool responses and applies clearance-based regex masking:
    - **Bangladeshi Mobile Phone:** `(?:\+?880|0)1[3-9]\d{8}` $\rightarrow$ `+8801*****89` or `[REDACTED_PHONE]`
    - **Bangladeshi National ID (NID):** `\b(?:\d{10}|\d{13}|\d{17})\b` $\rightarrow$ `[REDACTED_NID]`
    - **Email Address:** `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}` $\rightarrow$ `***@***.***`
    - Redaction is bypassed only if the calling agent possesses the `HIGH_CLEARANCE` role.
  - `OversizeResultGuard` (Order 300): Checks response byte size and array lengths. If response exceeds 64 KB or 100 items, truncates the payload and appends an explicit warning notice: `"[TRUNCATED: Response exceeded maximum allowed size of 64KB]"` (fulfilling FR5).

#### Task 2.2: Human Approval State Machine & Web/REST Workflow (FR4)
- **State Machine Specification (State Pattern):**
  - **States:** `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`.
  - **Transitions:**
    - `PENDING` $\xrightarrow{\text{approver approves}}$ `APPROVED` (triggers downstream execution).
    - `PENDING` $\xrightarrow{\text{approver rejects}}$ `REJECTED` (returns error to agent).
    - `PENDING` $\xrightarrow{\text{approver edits args}}$ `APPROVED` (executes with modified args).
    - `PENDING` $\xrightarrow{\text{timeout reached}}$ `EXPIRED` (cancels call, returns timeout to agent).
    - `PENDING` $\xrightarrow{\text{kill switch triggered}}$ `CANCELLED` (fails closed immediately).
- **Database Schema (`V4__approvals.sql`):**
  ```sql
  CREATE TABLE approval_requests (
      id UUID PRIMARY KEY,
      operation_id VARCHAR(100) UNIQUE NOT NULL, -- Client-provided idempotency key
      agent_id VARCHAR(100) NOT NULL,
      tool_name VARCHAR(100) NOT NULL,
      original_arguments JSONB NOT NULL,
      modified_arguments JSONB,
      status VARCHAR(20) NOT NULL, -- PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED
      decision_reason TEXT,
      decided_by VARCHAR(100),
      timeout_at TIMESTAMP WITH TIME ZONE NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      decided_at TIMESTAMP WITH TIME ZONE
  );
  ```
- **Workflow & UI Implementation:**
  - When policy returns `REQUIRE_APPROVAL`: gateway pauses execution, creates `approval_requests` row, and fires webhook notification to configured URL.
  - Client holds connection (HTTP Long-Polling or CompletableFuture suspended up to configurable timeout, e.g. 30s for demo, 120s max).
  - **Thymeleaf Admin Approval Page (`/admin/approvals`):** Responsive web table displaying pending requests with buttons to Approve, Reject, or Edit Arguments inline.
  - **REST API (`POST /api/v1/approvals/{id}/decision`):** Accepts `{ action: "APPROVE"|"REJECT", modifiedArguments: {...}, reason: "..." }`.
  - **Timeout Scheduler:** Background task running every 2 seconds marks expired requests as `EXPIRED`.
  - **Agent Response:** Returns clear, deterministic JSON-RPC response in all branches (`APPROVAL_TIMEOUT`, `APPROVAL_REJECTED`, or executed tool output).

#### Task 2.3: Limits, Blast-Radius Caps & Emergency Kill Switches (FR6, FR8)
- **Redis Rate Limiting & Blast-Radius Caps (`RedisRateLimitService`):**
  - Uses Redis Lua scripts for atomic sliding window / token bucket counting:
    - **Per-Agent Call Rate:** e.g., max 60 calls/minute.
    - **Per-Tool Call Rate:** e.g., max 120 calls/minute.
    - **Daily Quota:** e.g., max 5,000 calls/day per agent.
    - **Hourly Blast-Radius Cap on Write Tools:** e.g., max 20 write operations/hour per agent.
  - Breach Behavior: Returns HTTP 429 / JSON-RPC error `RATE_LIMIT_EXCEEDED` or `WRITE_CAP_EXCEEDED` and publishes a structured security alert log.
- **Emergency Kill Switches (`KillSwitchService`):**
  - In-memory thread-safe flags backed by Redis key-value store:
    - Disable specific Agent (`agentgate:kill:agent:{id}`).
    - Disable specific Tool (`agentgate:kill:tool:{name}`).
    - Global Kill Switch: Disable all write tools (`agentgate:kill:all_writes`).
  - REST Endpoints: `POST /api/v1/admin/kill-switch/{scope}` with Bearer token authentication.
  - Instant Action:
    - Any new call matching the active kill switch is rejected immediately with `SERVICE_UNAVAILABLE` / `KILL_SWITCH_ACTIVE`.
    - Automatically transitions all matching `PENDING` approval requests to `CANCELLED` and releases waiting client threads immediately.

#### Day 2 Exit Gate & Demonstration
1. **Automated Unit & Integration Tests:** `GuardPipelineTest`, `ApprovalStateMachineTest`, `RateLimiterTest`, and `KillSwitchTest` pass.
2. **Scripted Verification (`scripts/verify-day2.ps1` or `.py`):**
   - Submit call with invalid JSON types: verify rejected by `JsonSchemaValidationGuard` without contacting downstream.
   - Call tool returning customer data: verify Bangladeshi phone (`+88017...`), NID, and email are masked for standard agent, and unmasked for high-clearance agent.
   - Call high-risk tool requiring approval: verify execution pauses. Open `/admin/approvals`, approve request, verify client receives executed result.
   - Call high-risk tool, let timeout expire: verify client receives clear `APPROVAL_TIMEOUT` error.
   - Fire 21 write operations in rapid succession: verify 21st call is blocked by hourly write cap.
   - Trigger kill switch for write tools: verify subsequent write calls fail instantly and pending approvals are cancelled.

---

### Day 3: Audit Trail, Resilience, QA Acceptance & CI

**Primary Objectives:** Implement the tamper-evident SHA-256 audit WAL and verification CLI; implement Resilience4j circuit breakers and dynamic health filtering; execute full QA acceptance suites (QA1, QA2, QA3, QA5); establish GitHub Actions CI and finalize documentation for defense.  
**Requirement Coverage:** FR7, FR10, QA1, QA2, QA3, QA5, C1, C3, C5, C6, C7, C8, C9, C11, C12.

#### Task 3.1: Tamper-Evident SHA-256 Audit WAL & Verification CLI (FR7, QA3)
- **Database Schema (`V5__audit_wal.sql`):**
  ```sql
  CREATE TABLE audit_log (
      id BIGSERIAL PRIMARY KEY,
      event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      agent_id VARCHAR(100) NOT NULL,
      user_id VARCHAR(100),
      tool_name VARCHAR(100) NOT NULL,
      redacted_arguments JSONB NOT NULL,
      policy_rule_id VARCHAR(100),
      approval_id UUID,
      execution_latency_ms BIGINT NOT NULL,
      outcome VARCHAR(50) NOT NULL, -- ALLOWED, DENIED, APPROVED_EXECUTED, REJECTED, TIMEOUT, CIRCUIT_OPEN
      previous_hash VARCHAR(64) NOT NULL,
      record_hash VARCHAR(64) NOT NULL
  );

  CREATE INDEX idx_audit_agent ON audit_log(agent_id);
  CREATE INDEX idx_audit_tool ON audit_log(tool_name);
  CREATE INDEX idx_audit_timestamp ON audit_log(event_timestamp);
  ```
- **Cryptographic Chaining (`AuditService`):**
  - Compute record hash:  
    $$\text{record\_hash} = \text{SHA-256}(\text{previous\_hash} + \text{id} + \text{timestamp} + \text{agent\_id} + \text{tool\_name} + \text{redacted\_arguments} + \text{outcome})$$
  - Event publishing: Use Spring `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` to record events asynchronously without blocking request latency while guaranteeing committed state.
  - Search & Export Endpoints: `GET /api/v1/audit` with filtering (agent, tool, date range), pagination, and export formats (`?format=json` and `?format=csv`).
- **Audit Verification Command & Test (`AuditVerificationTest`):**
  - Traverses the entire audit log sequentially, recomputes the SHA-256 hash chain from row 0 to $N$, and verifies integrity.
  - Verifier detects any manually modified column, deleted row, or inserted row in $< 1\text{ second}$ (fulfilling QA3).

#### Task 3.2: Downstream Resilience & Dynamic Health Filtering (FR10)
- **Resilience4j Integration:**
  - Wrap downstream REST and MCP tool invocations with Resilience4j `TimeLimiter` (default 2000 ms timeout) and `CircuitBreaker` (sliding window of 10 calls, 50% failure rate threshold).
  - Slow calls or downstream connection errors trip the circuit breaker into `OPEN` state.
- **Dynamic Tool Health & Listing Visibility:**
  - Tool health listener monitors CircuitBreaker state transitions (`CircuitBreaker.Event.Type.STATE_TRANSITION`).
  - When a tool's circuit opens, the gateway automatically marks the tool as temporarily `UNHEALTHY` and filters it out of agents' `tools/list` responses.
  - When the circuit transitions to `HALF_OPEN` and successfully recovers to `CLOSED`, the tool is immediately restored to `tools/list`.

#### Task 3.3: QA Acceptance Suites & Benchmark Probes (QA1, QA2, QA5)
- **QA1 Red-Team Security Suite (`scripts/qa/qa1_security_suite.py`):**
  - **30 Malicious Vectors (Target: 100% Blocked or Held):**
    - 10 prompt injection / argument tampering attempts (smuggled commands, SQL syntax, oversized payloads).
    - 10 unauthorized financial transactions (refunds exceeding clearance, unpermitted tools, write calls to disabled tools).
    - 10 cross-customer access attempts (requesting other users' orders, unauthenticated spoofed JWTs).
  - **200 Legitimate Calls (Target: $\le 5\%$ False Blocks, i.e. $\le 10/200$):**
    - Scripted automated calls from permitted agents with valid arguments and tokens.
    - Generates structured report `qa1_results.json` proving 100% blocked and $\le 5\%$ false block rate.
- **QA2 Gateway Overhead Benchmark (`scripts/qa/qa2_performance_benchmark.py` / k6):**
  - Executes 100 calls/second load against gateway vs direct call to `demo-tools`.
  - Measures gateway-added latency overhead (excluding approval wait).
  - Asserts $p95 \le 30\text{ ms}$ gateway overhead.
- **QA5 New Tool Adoption Walkthrough (`docs/adoption/QA5_WALKTHROUGH.md`):**
  - Standalone tutorial: an unfamiliar developer imports a new REST OpenAPI file, assigns risk level `MEDIUM`, binds a policy, and executes a governed call in $< 10\text{ minutes}$ with zero Java code changes.

#### Task 3.4: CI Pipeline, ADRs, Pattern Table & Final Defense Package (C1–C12)
- **GitHub Actions CI Workflow (`.github/workflows/ci.yml`):**
  - Sets up Java 21 LTS; runs `mvn clean verify`; executes ArchUnit architectural layer tests; verifies Docker Compose startup and health probes.
- **Architecture Documentation & Defense Artifacts:**
  - Update `docs/architecture/` with:
    - **4 Empirical ADRs:** ADR-001 (Modular Monolith over Microservices), ADR-002 (Streamable HTTP MCP Transport), ADR-003 (SHA-256 Tamper-Evident Audit WAL), ADR-004 (Dynamic Dynamic Tool Filtering).
    - **Pattern Justification Table (C8):** Explicit mapping of Chain of Responsibility, Strategy, State, Observer, Proxy, Adapter, and Command patterns to exact requirements, classes, and rejected alternatives.
    - **Mini-ATAM Utility Tree:** $\ge 6$ scenarios with sensitivity and tradeoff points.
  - Finalize `docs/governance/AI_USAGE_LOG.md` with documented AI errors and remediation (ERR-008 through ERR-012).
  - Finalize `docs/governance/REQUIREMENTS_MATRIX.md` marking all items `VERIFIED` with links to reproducible scripts and test logs.

#### Day 3 Exit Gate & Final Project Milestone Closure
1. All 10 Functional Requirements (FR1–FR10) verified with green test outputs.
2. All 5 Quality Attributes (QA1–QA5) backed by raw benchmark files and passing scripts.
3. Common requirements C1–C12 fully satisfied; one-command Docker Compose runs within the 2.5 GB laptop budget; CI pipeline passes cleanly.

---

## 5. Architectural Decisions Requiring External Reviewer Approval

Before proceeding to code implementation, the following architectural choices are submitted for reviewer review and confirmation:

| # | Architecture Decision Point | Proposed Choice | Justification & Rejected Alternatives |
|---|---|---|---|
| **ADR-P1** | **Policy Evaluation Engine** | Structured Condition Evaluator (with strict allowlist fallback if SpEL needed) | **Approved Choice:** Simple structured JSON conditions (equality, numeric comparison, user ownership checks) evaluated directly against incoming arguments. Immune to injection, deterministic, and fast ($<0.1\text{ ms}$).<br>**Rejected Alternative:** Arbitrary general-purpose scripting or rule engines (Drools, full SpEL, OPA). |
| **ADR-P2** | **MCP Protocol Negotiation Baseline** | Streamable HTTP with negotiated `2024-11-05` / `2025-03-26` | **Approved Choice:** Spring AI MCP Core 2.1.0 natively supports both versions. We retain the working `/mcp` Streamable HTTP endpoint and accurately record the negotiated protocol version in audit logs.<br>**Rejected Alternative:** Custom transport rewrite or claiming unsupported protocol drafts (`2026-07-28`). |
| **ADR-P3** | **Approval State Machine Design** | POJO State Pattern with PostgreSQL persistence | **Approved Choice:** A dedicated POJO state pattern with atomic DB state transitions (`PENDING -> APPROVED/REJECTED/EXPIRED/CANCELLED`) is transparent, testable, and robust.<br>**Rejected Alternative:** `spring-statemachine` introduces heavy dependency baggage, complex serialization, and unnecessary configuration for a 5-state workflow. |
| **ADR-P4** | **Audit Log Durability Mechanism** | Durable PostgreSQL Hash Chain with local append-only fallback journal | **Approved Choice:** Durable synchronous/near-line recording into PostgreSQL with a persistent local append-only fallback journal on disk (`audit-fallback.log`) for DB outages. Fails closed before execution if neither sink is available.<br>**Rejected Alternative:** Distributed transaction coordinators or unpersisted async-only logging. |
| **ADR-P5** | **Rate Limiting & Blast Radius Storage** | Redis 7 Sliding Window via Lua Scripts | **Approved Choice:** Redis is already provisioned and running in the approved Docker Compose stack. Lua scripts guarantee atomic decrement/check operations without race conditions.<br>**Rejected Alternative:** In-memory bucket (Guava/Bucket4j) does not persist across gateway restarts and fails if multiple gateway instances are deployed. |

---

## 6. Requirements Traceability Matrix Alignment

The requirements traceability matrix is updated to reflect this three-day execution sequence honestly:

| ID | Paraphrased Requirement | Scheduled Day | Verification Artifact | Planned Status |
|---|---|---|---|---|
| **FR1** | Tool Registry & OpenAPI Import | Day 1 | `OpenApiImporterTest`, `ToolRegistryServiceTest` | PLANNED (Day 1) |
| **FR2** | Agent OAuth2 Identity & User Context | Day 1 | `JwtSecurityIntegrationTest`, Keycloak token tests | VERIFIED (Baseline M01) |
| **FR3** | Default-Deny Policies & Hot Reload | Day 1 | `PolicyEngineTest`, reload timer script | PLANNED (Day 1) |
| **FR4** | Human Approvals State Machine | Day 2 | `ApprovalStateMachineTest`, `/admin/approvals` UI | PLANNED (Day 2) |
| **FR5** | Input Schema & PII Redaction Guards | Day 2 | `GuardPipelineTest`, phone/NID/email fixtures | PLANNED (Day 2) |
| **FR6** | Rate Limits & Hourly Write Caps | Day 2 | `RateLimiterTest`, Redis boundary probe | PLANNED (Day 2) |
| **FR7** | Tamper-Evident SHA-256 Audit WAL | Day 3 | `AuditVerificationTest`, tamper injection script | PLANNED (Day 3) |
| **FR8** | Instant Kill Switches & Cancellation | Day 2 | `KillSwitchTest`, cancel pending approval probe | PLANNED (Day 2) |
| **FR9** | Policy Playground Endpoint | Day 1 | `PolicyPlaygroundControllerTest`, sample preview | PLANNED (Day 1) |
| **FR10** | Downstream Resilience & Health Filter | Day 3 | `ResilienceTest`, circuit open/close probe | PLANNED (Day 3) |
| **QA1** | 30 Malicious Blocked / 200 Legit $\le 5\%$ | Day 3 | `scripts/qa/qa1_security_suite.py` | PLANNED (Day 3) |
| **QA2** | $\le 30\text{ms}$ p95 Gateway Overhead | Day 3 | `scripts/qa/qa2_performance_benchmark.py` | PLANNED (Day 3) |
| **QA3** | Audit Query $<1\text{m}$, Tamper Detected | Day 3 | `AuditVerificationTest`, verify CLI script | PLANNED (Day 3) |
| **QA4** | Policy Outage Fail-Safe Availability | Day 1 | `PolicyOutageFailSafeTest` | PLANNED (Day 1) |
| **QA5** | Unfamiliar Dev Adds REST Tool $<10\text{m}$ | Day 3 | `docs/adoption/QA5_WALKTHROUGH.md` | PLANNED (Day 3) |
| **C1** | Java 21, Boot 4, Compose, $\le 8\text{GB}$ RAM | Day 1–3 | Docker Compose memory stats (~1,006 MiB measured) | BASELINE VERIFIED |
| **C2** | Config Profiles, Typed Props, No Secrets | Day 1–3 | Git secret scan, `.env.example` | BASELINE VERIFIED |
| **C3** | OpenAPI Springdoc, RFC 9457, Pagination | Day 1–3 | Swagger UI, `GlobalExceptionHandlerTest` | PARTIALLY VERIFIED |
| **C4** | Actuator, Micrometer, JSON Tracing Logs | Day 1–3 | Logback MDC traceId check, `/actuator/health` | BASELINE VERIFIED |
| **C5** | JUnit 5, Testcontainers, CI Runs All | Day 3 | GitHub Actions workflow, test execution logs | PLANNED (Day 3) |
| **C6** | ArchUnit Modular Monolith Verification | Day 1–3 | `ModularMonolithArchitectureTest` passes | BASELINE VERIFIED |
| **C7** | C4 Diagrams, 4 Empirical ADRs, Mini-ATAM | Day 3 | `docs/architecture/` diagrams, ADR files | PLANNED (Day 3) |
| **C8** | Pattern Justification Table | Day 3 | `docs/architecture/PATTERN_MAP.md` | PLANNED (Day 3) |
| **C9** | QA Evidence Report with Raw Charts | Day 3 | `docs/quality/QA_EVIDENCE_REPORT.md` | PLANNED (Day 3) |
| **C10** | Checkpoint Change Request Delta Note | M-CR | Ready for instructor issue | STANDBY |
| **C11** | Incremental Git & AI Usage Error Log | Day 1–3 | `AI_USAGE_LOG.md` (12 errors cataloged) | IN PROGRESS |
| **C12** | 15-Minute Defense Preparation Checklist | Day 3 | `docs/defence/DEFENCE_REHEARSAL.md` | PLANNED (Day 3) |

---

## 7. Daily Schedule, Verification Deliverables & Exit Criteria

```
+------------------------------------------------------------------------------------------------------+
| DAY 1: DELIVERABLES & EXIT CRITERIA                                                                 |
+------------------------------------------------------------------------------------------------------+
| Deliverables:                                                                                        |
| - Production SecurityConfig hardened (fallback removed, fail-fast enabled)                           |
| - Dedicated TestSecurityConfig isolated to src/test/java                                             |
| - Flyway V2__tool_registry.sql & V3__policies.sql migrations                                         |
| - OpenApiImporterService & RestToolAdapter implementation                                            |
| - Dynamic tools/list filtering wrapped around MCP tool provider                                      |
| - SpelPolicyEvaluator with in-memory cache, <=10s hot reload, and QA4 fail-safe                      |
| - Policy Playground API (POST /api/v1/policies/playground)                                           |
| Verification Script: scripts/verify-day1.py                                                          |
| Exit Gate: Dynamic filtered listing demonstrated; policy evaluation and hot reload verified in <=10s |
+------------------------------------------------------------------------------------------------------+

+------------------------------------------------------------------------------------------------------+
| DAY 2: DELIVERABLES & EXIT CRITERIA                                                                 |
+------------------------------------------------------------------------------------------------------+
| Deliverables:                                                                                        |
| - Pluggable ToolGuard pipeline (JsonSchemaValidationGuard, PiiRedactionGuard, OversizeResultGuard)   |
| - Bangladeshi phone, NID, and email regex redaction fixtures                                         |
| - Flyway V4__approvals.sql migration                                                                 |
| - Approval State Machine (PENDING -> APPROVED / REJECTED / EXPIRED / CANCELLED)                      |
| - Minimal Thymeleaf approval page (/admin/approvals) & REST decision endpoint                        |
| - Redis rate limiting (per-agent/tool) and hourly write blast-radius cap                             |
| - Emergency Kill Switches (disable agent, tool, all writes) with instant approval cancellation        |
| Verification Script: scripts/verify-day2.py                                                          |
| Exit Gate: High-risk call paused and approved via UI; PII masked; 21st write call blocked; kill switch|
+------------------------------------------------------------------------------------------------------+

+------------------------------------------------------------------------------------------------------+
| DAY 3: DELIVERABLES & EXIT CRITERIA                                                                 |
+------------------------------------------------------------------------------------------------------+
| Deliverables:                                                                                        |
| - Flyway V5__audit_wal.sql migration                                                                 |
| - SHA-256 hash-chained audit logging service with export API (JSON/CSV)                              |
| - Audit verification test proving manual row mutation is detected in <1 second                      |
| - Resilience4j CircuitBreaker & Dynamic Tool Listing Health filter (hide unhealthy tools)            |
| - QA1 Red-Team Suite (30/30 attacks blocked, <=5% false blocks)                                      |
| - QA2 Load Benchmark Script (p95 overhead <=30ms at 100 cps)                                         |
| - QA5 Classmate 10-Minute Adoption Walkthrough document                                              |
| - GitHub Actions CI workflow (.github/workflows/ci.yml)                                              |
| - Finalized 4 Empirical ADRs, Pattern Table, Mini-ATAM Utility Tree, and Defense Checklist           |
| Verification Script: scripts/verify-day3.py                                                          |
| Exit Gate: All QA1-QA5 scripts green; CI build passes; 100% requirements verified for live defense   |
+------------------------------------------------------------------------------------------------------+
```

---

## 8. Risk Management & Fallback Strategies

| Identified Risk | Probability | Impact | Mitigation Strategy |
|---|---|---|---|
| **Spring AI MCP Streamable Filter Interception:** Hooking into `tools/list` dynamically per agent identity might be constrained by Spring AI 2.0.2 internals. | Medium | Medium | If Spring AI's WebMVC transport evaluates registered tools globally before controller dispatch, we will wrap the underlying `McpServerTransportProvider` / `ToolCallbackProvider` in an agent-aware proxy that resolves the security context from `SecurityContextHolder` on each invocation. |
| **Redis Concurrency during Write Cap:** Burst traffic causes race conditions on write limit counters. | Low | High | Use atomic Redis Lua scripts (`redis.call('INCR')` with `EXPIRE`) to guarantee single-operation evaluation without distributed locks. |
| **Audit Hash Chain Contention:** High-throughput tool calls contending for the last hash in the audit chain. | Medium | Medium | The hash chain is computed and appended within an asynchronous, serialized transaction queue (`@TransactionalEventListener`), completely decoupled from the synchronous HTTP request/response latency. |
| **Docker Compose RAM Creep:** Adding Flyway migrations and additional beans pushes memory beyond laptop budget. | Low | High | Actual baseline RAM is ~1,006 MiB against a 2,500 MiB limit. All new components are in-process Spring beans within `apps/gateway` (no new container services). JVM `-Xmx384m` ensures memory remains strictly capped. |

---

## 9. Submission & Action Request

This plan represents a cohesive, rigorous, and fully defensible implementation blueprint that honors all course requirements and external reviewer feedback.

**Architectural Approval Request:**
We request the external architecture reviewer's verdict on:
1. The 3-day sequential structure (Day 1: Security & Governance, Day 2: Guards & Safety, Day 3: Audit, Resilience & CI).
2. The resolution of the five M01 remediation items.
3. The five architectural decisions (ADR-P1 through ADR-P5).

**Implementation Status:**
In accordance with governance rules, **no application code has been written and no dependencies have been installed**. Implementation of Day 1 will commence immediately upon receipt of the reviewer's explicit approval.
