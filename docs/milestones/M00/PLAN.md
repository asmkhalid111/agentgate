# Milestone M00 Plan: Baseline Architecture, Scope & Technical Feasibility (Revision 2)

**Status:** REVISED DRAFT (Incorporating Architecture Reviewer Revision 2 Final Corrections)  
**Milestone:** M00  
**Project:** CSE 444 Project 1 — AgentGate (Governance Gateway for AI Agents' Tool Calls)  
**Author:** Student Implementation Team  
**Reviewer:** External Architecture Reviewer (ChatGPT)  
**Date:** 2026-11-15  
**Primary Coverage:** C1, C7, C8, C11 (Foundational architectural planning for FR1–FR10, QA1–QA5, C1–C12)  

---

## 1. Executive Summary & Gated Operating Mode Declaration

This document constitutes the authoritative architecture baseline and technical feasibility plan for **Project AgentGate**, incorporating the targeted corrections mandated by **Revision 2** of the external architecture review.

In strict compliance with `AGENTS.md` and project governance:
- **This milestone is strictly PLAN ONLY.**
- **No application code, `pom.xml`, Docker Compose configurations, or production secrets have been created or modified.**
- **Approved components remain stable:** The modular-monolith structure, Java 21, Spring Boot 4.0.9, Spring AI 2.0.2, PostgreSQL 16, Redis 7, Keycloak 26, Thymeleaf, and milestone roadmap boundaries are firmly established and preserved.
- **Implementation (Milestone M01) will NOT begin until this revised plan receives an explicit `APPROVED` verdict from the external architecture reviewer (ChatGPT).**

---

## 2. Technology Baseline & Official Dependency Matrix

The technology stack is locked to **Java 21 LTS** and **Spring Boot 4.0.9**. All dependency versions are managed via official parent and BOM artifacts.

| Layer / Component | Official Dependency Coordinates / Version | Upstream Specification & Configuration Role | M01 Verification Spike |
|---|---|---|---|
| **Java JDK** | **Java 21 LTS** (Eclipse Adoptium Temurin) | Language runtime; Project Loom virtual threads, pattern matching, records. | Local pre-flight check (`java -version`). |
| **Spring Boot** | **4.0.9** (`spring-boot-starter-parent:4.0.9`) | Application framework, auto-configuration, HikariCP, Actuator. | Spike 1: Dependency resolution & clean context load. |
| **Spring AI BOM** | `org.springframework.ai:spring-ai-bom:2.0.2` | Dependency management BOM ensuring version convergence for all Spring AI modules. | Spike 1: Maven BOM import verification. |
| **Spring AI MCP Server** | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` | Official MVC starter exposing MCP endpoints over Streamable HTTP (`/mcp`). | Spike 2: Streamable HTTP connectivity test. |
| **Spring AI MCP Client** | `org.springframework.ai:spring-ai-starter-mcp-client` | Official MCP client starter for invoking downstream MCP tool servers. | Spike 2: Downstream client connectivity test. |
| **springdoc-openapi** | **3.0.x** (`springdoc-openapi-starter-webmvc-ui:3.0.x`) | OpenAPI 3.0 specification auto-generation and Swagger UI for Boot 4.0.x. | Spike 1: Auto-configuration validation. |
| **Spring Security** | Managed by Spring Boot 4 (Spring Security 7.x) | OAuth2 Resource Server, Keycloak JWT bearer validation, method security, CSRF. | Spike 2: Keycloak client credentials slice test. |
| **PostgreSQL** | **16-alpine** | Relational store for tool catalog, policies, approval states, and hash-chained audit. | Docker Compose health check & Flyway baseline. |
| **Flyway** | Managed by Spring Boot 4 (Flyway 10.x+) | Automated schema migrations (`db/migration/V1__initial_schema.sql`). | M01 DB migration test. |
| **Redis** | **7-alpine** | Atomic sliding-window rate counters, quotas, and distributed emergency kill switch. | Spike 3: Redis Lua script atomic increment check. |
| **Keycloak** | **26.x** | Standards-compliant OIDC / OAuth2 Identity Provider (running in dev mode). | M01 Compose realm import (`agentgate-realm.json`). |
| **Resilience4j** | **2.2.0** (Boot 4 compatible starter) | Downstream circuit breakers, rate limiters, retries, time limiters. | Configured in M01 base; verified in M08. |
| **ArchUnit** | **1.3.0** | Static architecture tests enforcing modular monolith boundaries (C6). | M01 automated boundary test suite. |
| **k6** | **0.50+** | Reproducible load testing and latency benchmarking (QA2). | Verified via `scripts/benchmark-latency.js`. |

> [!NOTE]
> **Compilation & Compatibility Assertion Policy:** In accordance with reviewer instructions, compatibility between Spring Boot 4.0.9, Spring AI 2.0.2, and springdoc-openapi 3.0.x is proposed based on upstream specifications. We explicitly **do not** claim these dependencies compile until an actual Maven build succeeds during M01. Resolving any dependency convergence issues is an explicit M01 verification task.

---

## 3. Environment Inventory & Prerequisites

A non-destructive, read-only inspection of the local workstation (`E:\Codes\Software-Architecture-Project`) confirmed:

| Prerequisite | Detected State | Impact on M00 | Resolution for Milestone M01 |
|---|---|---|---|
| **OS & Shell** | Windows 10/11, PowerShell 7.5.4 | Compatible | All commands documented with native PowerShell and Bash parity. |
| **Git** | `2.55.0.windows.3` | Ready | Initialized; baseline governance and M00 plan committed incrementally. |
| **Java JDK** | *Not detected on PATH* | Non-blocking for M00 | Student must run `winget install EclipseAdoptium.Temurin.21.JDK` before M01 code generation. *(Unverified locally until installed)*. |
| **Maven** | *Not detected globally on PATH* | Non-blocking for M00 | M01 will bundle the official Maven Wrapper (`mvnw` / `mvnw.cmd`), making a global Maven install optional. |
| **Docker / Compose** | *Not detected on PATH* | Non-blocking for M00 | Student must install Docker Desktop (`winget install Docker.DockerDesktop`) or configure WSL2 Docker engine before M01 runtime tests. |
| **Host Memory** | 16GB physical RAM | Ready | Comfortably satisfies the 8GB laptop constraint (C1). Compose stack memory target is capped to $\le 2.5\text{ GB}$. |

---

## 4. MCP Architecture & Protocol Compatibility

### 4.1 Official Starter Coordinates & Streamable HTTP Transport
- **Starter Coordinates:** The gateway includes the documented Spring AI starters managed via the Spring AI BOM:
  - `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`
  - `org.springframework.ai:spring-ai-starter-mcp-client`
- **Transport Configuration:** Streamable HTTP is explicitly configured in `application.yml`:
  ```yaml
  spring:
    ai:
      mcp:
        server:
          protocol: STREAMABLE
          streamable-http:
            path: /mcp
  ```
  The documented default endpoint is `/mcp`, configurable via `spring.ai.mcp.server.streamable-http.path`.
- **Protocol Versioning & Historical Accuracy:**
  - Streamable HTTP was introduced in the **`2025-03-26`** MCP specification (superseding legacy STDIO and raw SSE transports). It is **not** a `2024-11-05` transport.
  - Furthermore, the **`2026-07-28`** MCP specification has already been released upstream.
  - In accordance with reviewer guidance, **we do not assume out-of-the-box support for every MCP protocol version**. The gateway will implement standard protocol negotiation during client initialization (`initialize` handshake) and verify actual negotiated protocol versions in M01.
  - Spring AI's official MCP transport remains strictly preferred over a custom JSON-RPC implementation. A custom transport will only be considered if an irreconcilable incompatibility is discovered, documented in `docs/milestones/M01/PLAN.md`, and approved by the reviewer.

### 4.2 Dynamic Per-Identity Tool Discovery & Execution Security
1. **Dynamic Catalog Filtering (`tools/list`):** When an agent requests `tools/list` via JSON-RPC, the call passes through Spring Security. The authenticated `AgentIdentity` is extracted from the OAuth2 Bearer token. The gateway queries `ToolRegistryService.findPermittedTools(agentIdentity)` and projects a dynamic tool list containing **only** tools the agent is authorized to call.
2. **Authorized Invocation (`tools/call`):** Any `tools/call` request must specify a tool permitted for the caller. Calls to unauthorized or unmapped tools are rejected with JSON-RPC error `-32601` (Method Not Found) or `-32001` (Access Denied).

### 4.3 Minimal MCP Compatibility Test (M01 Acceptance Test)
An independent, scripted MCP client (`scripts/mcp-client-test.py` or `.ps1`) will be executed against the running gateway in M01 to demonstrate:
1. **Authenticated Connectivity:** Client connects to `/mcp` over Streamable HTTP with an OAuth2 Bearer token; unauthenticated requests receive HTTP 401.
2. **Protocol Negotiation:** Successful handshake capturing the negotiated MCP protocol version (confirming support for `2025-03-26` / `2026-07-28` baseline).
3. **Dynamic Tool Discovery:** Agent A (Finance clearance) lists tools and sees `[lookup_order, issue_refund]`; Agent B (Support clearance) lists tools and sees `[lookup_order]` only.
4. **Authorized Tool Execution:** Agent A executes `lookup_order` via `tools/call` and receives a structured JSON-RPC result.
5. **Rejection of Unauthorized Direct Invocation:** Agent B attempts `tools/call` on `issue_refund`; gateway rejects with JSON-RPC error `-32601` or `-32001`.
6. **Authentication Failure Handling:** Calling with an expired or forged JWT fails at the Spring Security filter with HTTP 401 before any MCP processing.
7. **Real MCP Message Compatibility:** Verified compliance with JSON-RPC 2.0 envelopes (`jsonrpc: "2.0"`, `id`, `method`, `params`, `result`, `error`).

---

## 5. Unified Governed Execution Pipeline Specification

Every tool invocation—originating from MCP `tools/call` or a REST gateway proxy—traverses this single, non-bypassable 11-step pipeline.

```mermaid
sequenceDiagram
    autonumber
    participant Client as MCP Agent Client
    participant Pipe as GovernedExecutionPipeline
    participant Ident as Identity & Anti-Spoof
    participant Kill as Emergency Kill Switch
    participant Reg as Registry & Resilience Filter
    participant Limit as Quotas & Rate Limits
    participant Schema as Schema Validation Guard
    participant Policy as Policy Engine (Default-Deny)
    participant Appr as Approval State Machine
    participant Target as Downstream Tool (REST/MCP)
    participant Redact as PII Redaction & Truncation
    participant Audit as Hash-Chain Audit Logger

    Client->>Pipe: execute(ToolCallRequest with operationId)
    Pipe->>Ident: 1. Extract Bearer JWT & verify azp-bound end-user assertion
    Pipe->>Kill: 2. Check atomic kill-switch (Agent, Tool, All Writes)
    Pipe->>Reg: 3. Verify tool exists & Circuit Breaker is CLOSED
    Pipe->>Limit: 4. Atomically RESERVE quota counter (Redis Lua)
    Pipe->>Schema: 5. Validate input arguments against JSON Schema
    Pipe->>Policy: 6. Evaluate default-deny policy rules

    alt Requires Human Approval (High-Risk or Policy Rule)
        Pipe->>Appr: 7. Create PENDING approval & notify approver
        alt Approver Edits Arguments
            Appr-->>Pipe: Re-validate Schema & Re-evaluate Policy
            Note over Pipe: If valid -> APPROVED; If invalid -> Remains PENDING
        else Approver Rejects, Expires, or Cancelled by Kill Switch
            Appr-->>Pipe: REJECTED / EXPIRED / CANCELLED
            Pipe->>Limit: Release reserved quota counter
            Pipe->>Audit: Record rejection in audit chain
            Pipe-->>Client: Error ("Call rejected by approver / timeout / kill switch")
        end
    end

    Note over Pipe,Target: Final Execution Authorization & Two-Phase Write
    Pipe->>Kill: 8a. Re-evaluate Kill Switch, Policy, Health, Limits & Final Args
    Pipe->>Audit: 8b. Record ToolExecutionIntent (DISPATCHED to WAL/DB)
    alt Intent Write Fails
        Pipe->>Limit: Release reserved quota counter
        Pipe-->>Client: Fail Closed (HTTP 500 / 503 "Audit intent durability failure")
    end
    Pipe->>Target: 8c. Invoke downstream tool via resilient client
    Target-->>Pipe: Raw Result Payload (JSON / String)

    Pipe->>Redact: 9. Redact PII (Email, BD Phone, NID) & truncate if oversized
    Pipe->>Limit: 10a. COMMIT reserved quota counter
    Pipe->>Audit: 10b. Record ToolExecutionOutcome & Append Hash-Chain (H_n)
    Pipe-->>Client: 11. Deliver scrubbed, governed result
```

---

## 6. Write-Tool Idempotency & Operation Lifecycle

### 6.1 Unique Operation Identifiers (Anti-Conflation Rule)
- **Reviewer Mandate:** Never derive operation identity solely from agent ID, tool ID, and argument hashes. Two legitimate calls with identical arguments (e.g. "transfer 100 BDT to Bob" twice) may represent separate, intentional business operations.
- **Client-Provided Operation Identifier:** Every write tool invocation **must** supply a unique `operationId` (UUIDv4) passed via the `_meta.idempotencyKey` field in the MCP JSON-RPC `params` or the HTTP `Idempotency-Key` header.
- **Scripted Client Support:** For scripted MCP clients, if the client omits `operationId` on a write call, the gateway generates a unique `operationId` and returns it in the response header/metadata for tracking.

### 6.2 Idempotency State & Retry Semantics
The gateway maintains an atomic operation registry in Redis (backed by PostgreSQL `tool_operations` table):

```text
Operation Record: {
  operationId: "uuid",
  agentId: "agent-1",
  toolId: "issue_refund",
  argumentHash: "sha256(canonicalJson(args))",
  status: "IN_FLIGHT" | "COMPLETED" | "FAILED" | "UNKNOWN_DOWNSTREAM_STATUS",
  responsePayload: "{...}",
  createdAt: timestamp,
  updatedAt: timestamp
}
```

1. **Retry with Same `operationId` and Identical Arguments:**
   - If `status == COMPLETED`: The gateway returns the cached response immediately from the audit/operation store without re-invoking the downstream tool.
   - If `status == IN_FLIGHT`: The gateway returns HTTP 409 Conflict / JSON-RPC error `-32009` (`"Operation in flight; retry after completion"`).
   - If `status == FAILED`: Returns the recorded error response.
2. **Reused `operationId` with Changed Arguments (Tamper/Collision Defense):**
   - If an incoming request presents an `operationId` already registered, but the canonical hash of the arguments does not match `argumentHash`:
   - **Immediate Rejection:** Returns RFC 9457 `422 Unprocessable Content` / JSON-RPC error `-32602` with detail `"Idempotency key reused with mismatched arguments"`. The operation is rejected and audited as an anomaly.
3. **Recovery When Downstream Succeeds but Response is Lost:**
   - If downstream execution succeeds but the network connection to the client drops before delivering the response, the gateway's post-invocation step has already updated the record to `COMPLETED` and committed the audit record.
   - When the client reconnects or retries with the same `operationId`, the gateway detects `status == COMPLETED` and returns the scrubbed result immediately.
4. **Behavior When Downstream Outcome is `UNKNOWN`:**
   - If the downstream HTTP call times out or throws an unconfirmed socket error after dispatch:
   - The operation status is set to `UNKNOWN_DOWNSTREAM_STATUS`.
   - **No Blind Retries:** A subsequent client retry with the same `operationId` will **not** blindly re-invoke the downstream write tool (which could cause double refunding).
   - The gateway returns HTTP 504 Gateway Timeout / JSON-RPC error `-32008` with message `"Downstream execution outcome unknown; manual reconciliation or downstream status inquiry required"`.

---

## 7. Authoritative Approval Lifecycle & Race-Free Execution

### 7.1 Authoritative State Set & Transition Table
To eliminate undeclared or contradictory states, the approval lifecycle is governed strictly by **five declared states**: `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, and `CANCELLED`.

| Current State | Trigger Event | Next State | Actions & Side Effects |
|---|---|---|---|
| **`PENDING`** | Approver clicks "Approve" (unmodified args) | **`APPROVED`** | Atomically transition state; unblock waiting thread; proceed to Step 8 (Pre-dispatch check). |
| **`PENDING`** | Approver edits arguments and submits | **`APPROVED`** *(if valid)*<br>**`PENDING`** *(if invalid)* | Synchronously re-validate edited args against Schema and Policy.<br>- If valid: record edited args, transition to **`APPROVED`**.<br>- If invalid: return validation errors to approver; remain **`PENDING`**. |
| **`PENDING`** | Approver clicks "Reject" | **`REJECTED`** | Atomically transition state; release reserved quota; notify client with reason. |
| **`PENDING`** | Configured timeout elapses | **`EXPIRED`** | Timer worker updates state atomically; release reserved quota; notify client of timeout. |
| **`PENDING`** | Kill switch activated for agent, tool, or writes | **`CANCELLED`** | Synchronously cancel approval; release reserved quota; notify client and approver. |
| **`PENDING`** | Client drops HTTP socket / disconnects | **`PENDING`** *(retained)* | Approval remains pending until timeout or human decision. Client may reconnect via `operationId`. |

> [!IMPORTANT]
> **No Intermediate Pseudo-States:** States such as `APPROVED_WITH_EDITS`, `CANCELLED_PRE_DISPATCH`, or `DISCONNECTED_PENDING_OUTCOME` are eliminated. Edited arguments are stored as fields (`editedArguments`, `editedBy`) on the `APPROVED` record. Disconnections do not alter the lifecycle state; the approval remains `PENDING` until resolved.

### 7.2 Atomic Transitions & Race Prevention
- **Database Row Versioning:** The `approvals` table uses optimistic concurrency control (`@Version int version`).
- **Atomic Compare-And-Swap:** State transitions execute via single conditional SQL updates:
  ```sql
  UPDATE approvals 
  SET status = :newStatus, version = version + 1, updated_at = NOW() 
  WHERE id = :id AND status = 'PENDING';
  ```
- **Race Prevention Guarantee:** Exactly one actor (approver, timeout worker, or kill-switch handler) can transition the record away from `PENDING`. If an approver approves simultaneously with a timeout, only the first database commit succeeds; the second receives an optimistic lock failure and aborts cleanly.
- Downstream dispatch is **only** initiated if the transition to `APPROVED` was successfully committed.

### 7.3 Client Disconnection & Reconnection Behavior
- **Disconnection Does Not Cancel:** When an agent client disconnects while a request is in `PENDING` status, the approval is **not** cancelled. It remains `PENDING` in the database until an approver acts or the configured timeout (`agentgate.approval.default-timeout: PT3M`) expires.
- **Safe Reconnection:** The client reconnects or retries by sending the same `operationId`.
  - If the approval is still `PENDING`, the connection attaches to the in-flight approval (waiting on latch or receiving pending polling status).
  - If the approval transitioned to `APPROVED` and executed, the client receives the cached `COMPLETED` result.
  - If the approval transitioned to `REJECTED`, `EXPIRED`, or `CANCELLED`, the client receives the definitive error payload.

---

## 8. Final Pre-Dispatch Authorization & Limit Reservation

### 8.1 Pre-Dispatch Authorization Re-check (Step 8a)
Immediately before invoking the downstream tool client, the virtual thread executes a mandatory, non-cached pre-dispatch assertion:
1. **Agent Active Status & Tool Clearance:** Re-verify that the calling agent has not been deactivated and that the tool clearance remains valid.
2. **Current Applicable Policies:** Re-verify that no policy rule was deleted, disabled, or modified during the approval waiting period.
3. **Validated Final Arguments:** Ensure the arguments sent to the downstream invoker match the exact validated arguments (incorporating approver edits).
4. **Emergency Kill Switches:** Synchronously assert that neither the agent, the specific tool, nor all write tools have been disabled via the kill switch.
5. **Downstream Health:** Check that the target tool's circuit breaker is `CLOSED` or `HALF-OPEN`.
6. **Applicable Limits:** Confirm limit reservations remain valid.

> [!CAUTION]
> **Revocation Rule:** An operation must **never** execute if relevant authorization was revoked while the request was waiting for approval. If any pre-dispatch check fails, downstream invocation is aborted immediately, the approval record is marked `CANCELLED`, the reserved quota is released, and the incident is audited.

### 8.2 Two-Phase Limit Reservation (Preventing Double-Charging)
To ensure concurrent requests do not exceed rate limits while pending approval, without charging failed or rejected calls:
- **Phase 1: Reserve Counter (Step 4):**
  - Upon request arrival, the gateway atomically reserves quota in Redis via Lua script:
    ```lua
    -- Increment reserved counter if within quota limit
    if redis.call('get', KEYS[1]) and tonumber(redis.call('get', KEYS[1])) >= tonumber(ARGV[1]) then
        return 0 -- Quota exceeded
    else
        redis.call('incr', KEYS[1])
        return 1 -- Reserved successfully
    end
    ```
- **Release on Abort/Rejection:**
  - If the request is rejected by policy, rejected by human, expires, or is aborted pre-dispatch: the gateway calls `DECR KEYS[1]`, releasing the reserved capacity.
- **Phase 2: Commit Counter (Step 10a):**
  - Upon successful downstream execution, the reservation transitions to `COMMITTED`. The counter is not incremented again.
- **Outcome:** Limits are accurately enforced under concurrency; rejected/cancelled approvals do not deplete quotas; operations are never double-charged.

---

## 9. Trusted End-User Delegation & Identity Anti-Spoofing

### 9.1 The Authorized Party (`azp`) Delegation Model
- **Security Flaw in Generic Assertion Tokens:** A signed user JWT combined with an agent's generic OAuth2 scope is insufficient because an agent could replay a user token obtained in another context without proof that the user authorized that specific agent.
- **Selected Implementation: Signed User Assertion Bound to Agent Client ID (RFC 7519 / RFC 8693):**
  - When an agent acts on behalf of an end user, it must provide a signed JWT assertion in the `X-User-Assertion` header.
  - The token is signed by Keycloak (or a trusted IdP) and **must** explicitly name the calling agent as the authorized party:
    ```json
    {
      "sub": "customer-456",
      "iss": "https://auth.agentgate.local/realms/agentgate",
      "aud": "agentgate-gateway",
      "azp": "agent-finance-bot",
      "scope": "tool:delegate",
      "exp": 1773543600
    }
    ```
- **Gateway Verification Protocol:**
  1. Extract calling agent's identity (`clientId`) from the verified OAuth2 Bearer token (`agent-finance-bot`).
  2. Verify that the agent token contains the OAuth2 scope `delegation:act_as_user`.
  3. Validate the `X-User-Assertion` JWT signature against Keycloak JWKS public keys.
  4. Assert `assertion.claims["azp"] == agent.clientId`. If mismatched, **reject immediately (HTTP 401)** with detail `"User assertion was not authorized for calling agent."`
  5. Assert assertion is unexpired (`exp`) and active (`nbf`).
  6. Bind `userId = assertion.claims["sub"]` into the `SecurityContext`.

### 9.2 Tests for Cross-User Impersonation Defense
The test suite (`UserDelegationSecurityTest`) explicitly validates:
1. **Unsigned User Header:** Agent sends `X-User-Id: customer-456` without assertion token $\to$ Rejected (HTTP 401).
2. **Token Replay / Mismatched Agent (`azp` mismatch):** Agent A presents a valid user assertion minted for Agent B (`azp: "agent-b"`) $\to$ Rejected (HTTP 401, `"Client mismatch"`).
3. **Tampered Subject Claim:** Agent modifies `sub` in assertion without valid signature $\to$ Rejected (HTTP 401, `"Invalid JWT signature"`).
4. **Cross-Customer Data Access:** Agent A (with valid assertion for Customer X) attempts to read Order belonging to Customer Y $\to$ Denied by Policy Engine (Rule matched: `customer_ownership_check`).

### 9.3 Keycloak Web Roles & CSRF Protection
- **Web UI Roles:**
  - `ROLE_ADMIN`: Allowed on `/admin/**` (tool import, policy creation, kill switches).
  - `ROLE_APPROVER`: Allowed on `/approvals/**` (view pending, approve, reject, edit).
- **CSRF Defense:**
  - Enabled on browser-facing endpoints (`/admin/**`, `/approvals/**`) using Spring Security `CookieCsrfTokenRepository` (Thymeleaf automatically inserts `_csrf` tokens).
  - Stateless API endpoints (`/mcp/**`, `/api/**`) authenticate strictly via Bearer tokens and are exempt from CSRF.

---

## 10. Audit Durability, Crash-Safe WAL & Reconciliation

### 10.1 Storage Infrastructure & Volume Mounting
- In `compose.yaml`, the gateway mounts a persistent volume for the audit WAL:
  ```yaml
  services:
    gateway:
      volumes:
        - agentgate-audit-wal:/var/agentgate/wal
  volumes:
    agentgate-audit-wal:
  ```
- In local development, `/var/agentgate/wal` maps to a local directory (`./var/audit-wal`).

### 10.2 Crash-Safe Binary Record Framing & Incomplete-Record Detection
To protect against partial writes during abrupt container termination or power failure:
- **Binary Frame Specification:**
  ```text
  ┌───────────────┬────────────────┬──────────────────────────┬────────────────┐
  │ MAGIC (2B)    │ LENGTH (4B)    │ PAYLOAD (JSON, N bytes)  │ CRC32 (4B)     │
  │ 0x41 0x47     │ uint32         │ Canonical JSON string    │ uint32         │
  └───────────────┴────────────────┴──────────────────────────┴────────────────┘
  ```
- **Synchronous Disk Flushing:** Every record write executes `FileChannel.force(true)` (`fsync`), ensuring the OS buffer is flushed to physical storage before proceeding.
- **Incomplete-Record Detection on Recovery:**
  - On restart, the `AuditReconciliationWorker` scans `audit-deferred.wal`.
  - For each frame: reads 2 magic bytes (`0x4147`) and 4-byte payload length $L$.
  - If the file terminates before $L$ bytes or the computed CRC32 checksum does not match the trailing 4 bytes:
    - The corrupted/truncated frame is isolated and logged to `corrupted-wal.log`.
    - Reconciliation stops at the last uncorrupted record, preventing corrupt payload ingestion.

### 10.3 Idempotent Replay & Continuous Reconciliation Ordering
1. **Idempotent Insertion:** Each WAL record contains the unique `operationId`.
2. When PostgreSQL recovers, `AuditReconciliationWorker` queries `audit_log WHERE operation_id IN (:walOperationIds)`. Any record already committed is skipped.
3. **Sequential Hash Continuity:**
   - The worker acquires an exclusive row lock on the audit chain head (`SELECT hash FROM audit_log ORDER BY sequence_id DESC LIMIT 1 FOR UPDATE`).
   - For each uncommitted WAL record in chronological order:
     $$H_n = \text{SHA-256}(H_{n-1} \parallel \text{walPayload}_n)$$
   - Inserts record into `audit_log` with `reconciled_from_outage = true`, original timestamp, and the continuous global hash $H_n$.
4. **Atomic Truncation:** Once committed, `audit-deferred.wal` is atomically truncated or rotated to an archive segment.

### 10.4 Failure Modes: Downstream Succeeded vs Outcome Persistence Failed
- **Phase 1 Intent Must Be Durable:** If the gateway cannot durably record `ToolExecutionIntent` to PostgreSQL or the WAL buffer prior to dispatch, **it fails closed immediately without calling the downstream tool**.
- **Downstream Succeeded but Outcome Persistence Fails:**
  - If the downstream tool returns HTTP 200, but writing the outcome record to both PostgreSQL and the local WAL fails (e.g. disk full and DB down):
  - The gateway writes an emergency log entry to `stderr` with `operationId` and payload.
  - The client receives HTTP 500 (`"Execution succeeded downstream, but outcome recording failed. Operation ID: [X]"`).
  - Because `ToolExecutionIntent` was recorded prior to dispatch, an administrator can query `demo-tools` using `operationId` to confirm state and reconcile manually.

---

## 11. Quality-Attribute Validation Methodology

### 11.1 QA1: Security Red-Team Test Suite (30 Malicious + 200 Legitimate)
- **30 Scripted Malicious Requests (100% blocked or held):**
  - **10 Argument Smuggling / Injection:** SQL injection, JSON smuggling, and prompt injection in parameters.
    - *Distinction:* Rejections are categorized by enforcement layer:
      - 4 blocked by **Schema Guard** (type/regex format violations).
      - 4 blocked by **Policy Engine** (unauthorized parameter conditions).
      - 2 held for **Human Approval** and rejected.
  - **10 Unauthorized Cross-Customer Access:** Calling with unverified headers, mismatched `azp` tokens, or customer ID parameter mismatches $\to$ 100% blocked by Identity/Policy layers.
  - **10 Policy & Quota Breaches:** Refunds $> 2,000\text{ BDT}$, calls after hourly write cap exceeded, calls during active kill switch $\to$ 100% blocked.
  - *Harmless String Verification:* Benign strings containing punctuation (e.g. `"Notes: order delivered safely; thanks!"`) are safely validated by schema guards and processed downstream.
- **200 Legitimate Requests ($\le 5\%$ false block rate, max 10/200):**
  - **150 Low-Risk Read Operations:** Legitimate order inquiries distributed across valid customer sessions within configured rate limits.
  - **50 Permitted Write Operations (Compatible with Rate & Approval Limits):**
    - Distributed across 3 distinct authorized agents (18, 18, and 14 writes) to stay within the blast-radius cap of 20 writes/hour/agent.
    - Legitimate refunds ($\le 2,000\text{ BDT}$) trigger approvals that are automatically approved by a test webhook script within 1 second.
- **Success Measure:** 30/30 malicious calls blocked or held; $\le 10/200$ legitimate calls falsely blocked.

### 11.2 QA2: Performance Measurement Protocol ($\Delta p95 \le 30\text{ ms}$ at 100 cps)
Reproducible k6 load script (`scripts/benchmark-latency.js`):
1. **Identical Load Profile:** Sustained 100 calls/second constant arrival rate across 10 concurrent simulated agents calling read tools.
2. **Direct Downstream Baseline:**
   - 30-second warm-up at 20 cps.
   - 120-second steady-state execution at 100 cps directly against `apps/demo-tools`.
   - Record baseline latency distribution ($p50_{direct}, p95_{direct}, p99_{direct}$).
3. **Gateway Load Execution:**
   - 30-second warm-up at 20 cps.
   - 120-second steady-state execution at 100 cps through the AgentGate gateway.
   - Record gateway latency distribution ($p50_{gateway}, p95_{gateway}, p99_{gateway}$).
4. **Repetitions & Reporting:**
   - Execute 3 consecutive independent test runs.
   - Report the median $p95$ overhead across the runs:
     $$\Delta p95 = \text{median}(p95_{gateway}) - \text{median}(p95_{direct}) \le 30\text{ ms}$$
5. **Limitations of p95 Comparison:**
   - Local loopback network latency exhibits lower jitter than cross-cloud production networks.
   - Downstream mock tools respond with minimal variance compared to real enterprise microservices.
   - Synchronous SHA-256 audit database writes represent the primary overhead contributor; HikariCP connection pool tuning and prepared statements are essential to staying under 30ms.
   - **No unmeasured estimates:** Numbers reported during M00 are design targets; empirical results will only be reported when measured in M10.

### 11.3 C1: 8GB Laptop Resource Budget & Verification
- **Target:** Total container RAM consumption $\le 2.5\text{ GB}$.
- **Container Memory Limits in `compose.yaml`:**
  - `gateway`: `mem_limit: 1024m` (JVM heap `-Xmx512m`)
  - `keycloak`: `mem_limit: 768m` (JVM heap `-Xmx384m`)
  - `postgres`: `mem_limit: 256m`
  - `redis`: `mem_limit: 128m`
  - `demo-tools`: `mem_limit: 256m`
- **Verification:** Run `docker stats --no-stream` under full 100 cps load. Verify that total container memory $\le 2.5\text{ GB}$ and host retains $>5\text{ GB}$ available physical memory. Container memory limits alone do not prove compatibility; actual measured runtime consumption will be reported in M01/M10.

---

## 12. Strict Milestone Boundaries & Narrow M01 Scope

To prevent scope creep and maintain strict delivery discipline:

### 12.1 Milestone M01 Narrow Responsibilities (Foundation Only)
- Root aggregator Maven setup (`pom.xml`, `apps/gateway`, `apps/demo-tools`).
- Maven wrapper generation (`mvnw`, `mvnw.cmd`).
- Docker Compose configuration (`compose.yaml`) with Keycloak 26, Postgres 16, Redis 7, Demo-Tools.
- Basic Spring Boot 4.0.9 application with Spring Security OAuth2 resource server.
- **Spike 1:** Dependency compilation and clean context startup validation.
- **Spike 2:** Minimal authenticated MCP Streamable HTTP connectivity test (handshake, protocol negotiation, authenticated `tools/list` and `tools/call`).
- Spring Boot Actuator health and readiness endpoints (`/actuator/health`).
- Basic CI smoke test workflow.

### 12.2 Explicit Exclusions from Milestone M01
- **Full Approval State Machine:** Excluded from M01 $\to$ Implemented in **M05**.
- **Audit WAL Buffer & Outage Reconciliation:** Excluded from M01 $\to$ Implemented in **M07**.
- **Full Quota Engine & Concurrency Limits:** Excluded from M01 $\to$ Implemented in **M06**.
- **Admin Portal & Policy Playground:** Excluded from M01 $\to$ Implemented in **M03 / M09**.
- **Instructor Checkpoint Change Request:** Preserved as an isolated milestone $\to$ **M-CR**.

---

## 13. Architecture Decision Records (ADRs) & Pattern Mapping

### 13.1 Four Core ADR Candidates
1. **ADR-0001: Selection of Spring MVC with Virtual Threads over Spring WebFlux**
   - *Decision:* Adopt Spring MVC with Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`).
   - *Rationale:* Eliminates reactive cognitive overhead and context-propagation traps (`SecurityContextHolder`) while achieving near-reactive I/O concurrency with clean imperative code.
2. **ADR-0002: Modular Monolith with Package Boundary Enforcement over Microservices**
   - *Decision:* Deploy a single modular monolith (`apps/gateway`) with boundaries enforced by ArchUnit (C6).
   - *Rationale:* Eliminates distributed latency and network failure modes; satisfies 8GB RAM budget and single-command startup (C1).
3. **ADR-0003: Redis Atomic Sliding-Window and Key-Value Store with Fail-Closed Outage Semantics**
   - *Decision:* Use Redis 7 with Lua scripts for rate limits and kill switches; fail closed on Redis outage for write operations.
   - *Rationale:* Sub-millisecond atomic counter operations prevent concurrency races; fail-closed behavior ensures security integrity.
4. **ADR-0004: SHA-256 Cryptographic Hash-Chained Audit Trail with Local WAL Outage Fallback**
   - *Decision:* Append-only audit table with cryptographic hash chaining $H_n = \text{SHA-256}(H_{n-1} \parallel R_n)$ and local disk WAL fallback during database outages.
   - *Rationale:* Guarantees mathematical tamper-evidence (QA3) and satisfies FR7 durability even during PostgreSQL outages.

### 13.2 Design Pattern Justification Ledger (C8)

| Pattern | Requirement | Candidate Location | Participating Classes | Rejected Alternative & Rationale |
|---|---|---|---|---|
| **Chain of Responsibility** | FR3, FR5 | `execution`, `guard` | `PipelineStage`, `SchemaValidationStage`, `PolicyEvaluationStage`, `RedactionStage` | Hardcoded monolithic method: rejected because adding guards must not modify existing core code. |
| **Strategy** | FR3, FR5 | `guard`, `policy` | `RedactionStrategy`, `EmailRedactionStrategy`, `BangladeshiPhoneRedactor`, `NidRedactor` | Giant regex switch block: rejected because clearance-based redaction requires modular algorithms. |
| **Decorator / Proxy** | FR1, FR10 | `execution`, `resilience` | `GovernedToolInvoker`, `ResilientToolInvokerProxy` | Direct HTTP client calls: rejected because circuit breakers and timeouts must wrap execution transparently. |
| **Adapter** | FR1 | `registry.adapter` | `ToolExecutionAdapter`, `RestToolAdapter`, `McpDownstreamAdapter` | Separate execution paths for REST and MCP: rejected because all tools must share a single internal representation. |
| **State** | FR4 | `approval` | `ApprovalState`, `PendingState`, `ApprovedState`, `RejectedState`, `ApprovalStateMachine` | Raw database status flags with ad-hoc conditionals: rejected because approval transitions must be strictly enforced. |
| **Observer** | FR4, FR6, FR7 | `approval`, `audit` | `ApprovalEventPublisher`, `WebhookNotificationListener`, `AuditEventListener` | Synchronous method chaining: rejected because slow notification webhooks must not fail core transactions. |
| **Command** | FR1, FR4, FR7 | `execution` | `ToolExecutionCommand`, `CommandContext`, `CommandResult` | Unstructured JSON maps: rejected because audit, replay, and human approval edits require strongly-typed immutable commands. |

---

## 14. Mini-ATAM Utility Tree Scenarios (C7)

| ID | Driver | Stimulus | Environment | Target Response Measure | Sensitivity & Trade-Off |
|---|---|---|---|---|---|
| **S1** | **Security** (QA1) | 30 scripted malicious tool calls. | Production load. | 100% (30/30) blocked or held; legitimate false blocks $\le 5\%$. | Sensitivity: Rule strictness. Trade-off: Security vs false-positive usability. |
| **S2** | **Performance** (QA2) | Sustained 100 cps load across 10 agents. | Steady state. | Added latency $\Delta p95 \le 30\text{ ms}$. | Sensitivity: Audit write speed. Trade-off: Cryptographic durability vs latency. |
| **S3** | **Auditability** (QA3) | Auditor queries trace; row 42 modified. | Audit review. | Context found in $<1\text{ min}$; tamper detected by `verify-audit`. | Sensitivity: Argument completeness. Trade-off: Tamper-evidence vs storage size. |
| **S4** | **Availability** (QA4) | PostgreSQL database crashes. | Production gateway. | Medium/High fail closed immediately; Low works on cache $\le 5\text{ min}$ with WAL audit. | Sensitivity: Cache TTL. Trade-off: Low-risk availability vs stale policy risk. |
| **S5** | **Modifiability** (QA5) | External developer adds REST tool via OpenAPI. | Admin web portal. | Tool imported and governed in $<10\text{ min}$ with zero code changes. | Sensitivity: Dynamic schema registry. Trade-off: Runtime flexibility vs compile-time types. |
| **S6** | **Safety / Operability** | Admin triggers kill switch for compromised agent. | Under active attack. | Target agent blocked immediately; all pending approvals cancelled. | Sensitivity: Invalidation speed. Trade-off: Atomic flag check vs pure in-memory caching. |
| **S7** | **Resilience** | Downstream tool server crashes. | Peak traffic. | Circuit breaker opens after 5 failures; tool hidden from `tools/list`. | Sensitivity: Breaker thresholds. Trade-off: Fast failure suppression vs premature masking. |
| **S8** | **Resource Fit** (C1) | Full stack started via `docker compose up -d`. | 8GB RAM host. | Healthy in $<90\text{ s}$; total container RAM $\le 2.5\text{ GB}$. | Sensitivity: JVM heap sizing. Trade-off: Low memory footprint vs GC pause latency. |

---

## 15. Milestone M00 Acceptance Criteria & Reviewer Sign-Off Gate

### 15.1 M00 Exit Gate Criteria
1. **Revision 2 Final Corrections Complete:** All 8 items from Architecture Review Revision 2 fully incorporated.
2. **Official MCP Starter Coordinates:** `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client` configured via Spring AI BOM; Streamable HTTP on `/mcp`; MCP specification `2025-03-26` / `2026-07-28` acknowledged.
3. **Explicit Write Idempotency:** Client-provided `operationId` decoupled from arguments; rejection of mismatched argument reuses; recovery for lost responses; non-retry on unknown status.
4. **Authoritative Approval State Machine:** Strictly 5 declared states (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`); client disconnection retains pending state; race-free CAS updates.
5. **Final Pre-Dispatch Re-check & Limit Reservation:** Re-evaluation of kill switches, policy, health, and final arguments; two-phase reservation preventing double-charging.
6. **Audited User Delegation (`azp` bound):** User assertion token cryptographically bound to calling agent client ID (`azp`), preventing token replay across agents.
7. **Crash-Safe Audit WAL:** Binary framing with CRC32 checksums, persistent volume, and idempotent continuous reconciliation.
8. **Narrow M01 Boundary:** Foundation only; complex features strictly reserved for M03–M09.
9. **Zero Premature Code:** Strictly no application code, `pom.xml`, or Docker Compose files generated.

### 15.2 Specific Decisions Submitted for Architecture Reviewer (ChatGPT) Approval
1. **Ratification of Official MCP Starter Coordinates & Protocol Handling:** Acceptance of `spring-ai-starter-mcp-server-webmvc`, Streamable HTTP `/mcp`, and verification of negotiated protocol versions in M01.
2. **Ratification of Operation Idempotency Model:** Acceptance of explicit client `operationId` and two-phase intent/outcome recording.
3. **Ratification of 5-State Approval Machine & Disconnection Retention:** Acceptance of the single transition table and rule that disconnection leaves requests pending until timeout.
4. **Ratification of `azp`-Bound User Delegation:** Acceptance of the authorized-party verification protocol for `X-User-Assertion` tokens.
5. **Final Approval to Proceed to M01:** Issuance of an official verdict of **`APPROVED`** to begin Milestone M01 skeleton setup.

---
*End of Milestone M00 Plan (Revision 2).*
