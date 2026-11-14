# Milestone M01 Completion Report: Environment and Secure Application Foundation

**Status:** IMPLEMENTED — Ready for External Reviewer Verification  
**Milestone:** M01  
**Project:** CSE 444 Project 1 — AgentGate  
**Author:** Student Implementation Team  
**Reviewer:** External Architecture Reviewer (ChatGPT)  
**Date:** November 15, 2026  
**Approved Plan Reference:** `docs/milestones/M01/PLAN.md`  
**Git Branch / Commit:** `master` (Implementation Baseline)  

---

## 1. Executive Summary & Verification Declaration

Milestone M01 establishes the foundational infrastructure, build harness, containerized multi-service development environment, and core security subsystem for Project AgentGate. All mandatory reviewer conditions from M00 and M01 planning reviews have been strictly enforced and empirically validated.

- **Build Status:** PASSED (`BUILD SUCCESS`, 10/10 automated tests passing across reactor modules)
- **Docker Compose Status:** HEALTHY (All 5 containers: `gateway`, `demo-tools`, `keycloak`, `postgres`, `redis` operational and healthy)
- **MCP Spike Status:** VERIFIED (7/7 tests passed: unauthenticated 401 rejection, unauthorized 403 rejection, Streamable HTTP initialization, session ID negotiation, static tool discovery, permitted execution, restricted admin tool rejection without authority, and restricted admin tool success with authority)
- **Memory Measurement:** 1,006.6 MiB total actual container RAM consumed ($\le 2.5\text{ GB}$ hard ceiling strictly satisfied)
- **Security & Networking:** Decoupled Keycloak issuer validation (`http://localhost:8180/realms/agentgate`) vs internal JWKS URI (`http://keycloak:8080/realms/agentgate/protocol/openid-connect/certs`) verified with cryptographic signature verification and `iss` claim checks active.

---

## 2. Changed & Created Files Inventory

### Root & Build Configuration
- `pom.xml`: Root Maven aggregator defining managed dependencies for Java 21, Spring Boot 4.0.9, Spring AI 2.0.2 BOM, and Springdoc OpenAPI 3.0.3.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`: Maven 3.9.9 wrapper.
- `compose.yaml`: 5 services with hard memory ceilings totaling 2.37 GB.
- `.env.example`: Non-functioning illustrative placeholder credentials.

### `apps/gateway` (Core Modular Monolith)
- `apps/gateway/pom.xml`: Gateway dependencies including WebMVC, Security, OAuth2 Resource Server, Spring AI MCP Server WebMVC, Postgres, Redis, Flyway, Actuator, ArchUnit.
- `apps/gateway/Dockerfile`: Hardened Alpine JRE container with curl healthcheck.
- `apps/gateway/src/main/java/com/agentgate/gateway/GatewayApplication.java`: Main application entry point.
- `apps/gateway/src/main/java/com/agentgate/gateway/config/SecurityConfig.java`: Spring Security 7.x filter chain with stateless CSRF exemption for `/mcp/**`, dynamic `JwtDecoder` with canonical issuer validation and internal JWKS endpoint resolution.
- `apps/gateway/src/main/java/com/agentgate/gateway/config/McpConfig.java`: Wires `ToolCallbackProvider` into Spring AI McpServer specification.
- `apps/gateway/src/main/java/com/agentgate/gateway/identity/`: Core identity classes:
  - `AgentIdentity.java`: Record capturing `clientId`, `scopes`, and `roles`.
  - `UserContext.java`: Record for delegated end-user context.
  - `AgentAuthenticationToken.java`: `AbstractAuthenticationToken` implementation for agent credentials.
  - `JwtAgentAuthenticationConverter.java`: Converts JWT claims (`azp`, `scope`, `realm_access.roles`) into `AgentAuthenticationToken`.
  - `AgentContextHolder.java`: ThreadLocal security context accessor.
- `apps/gateway/src/main/java/com/agentgate/gateway/mcp/DemoToolsMcpProvider.java`: Static M01 test fixtures:
  - `m01_status`: Permitted tool accessible with `SCOPE_agent:invoke`.
  - `m01_restricted_admin_tool`: Restricted tool requiring `ROLE_ADMIN` or `tool:admin` authority.
- `apps/gateway/src/main/java/com/agentgate/gateway/common/`: Standard RFC 9457 ProblemDetail error handling (`ErrorCodes.java`, `GlobalExceptionHandler.java`).
- `apps/gateway/src/main/resources/`:
  - `application.yml`: Base configuration (Streamable HTTP protocol on `/mcp`, structured logging with MDC traceId/spanId).
  - `application-dev.yml`: Container profile with decoupled Keycloak hostnames.
  - `application-test.yml`: Slice test configuration.
  - `db/migration/V1__m01_baseline_schema.sql`: Initial Flyway baseline migration.

### `apps/demo-tools` (Downstream REST & Tool Fixtures)
- `apps/demo-tools/pom.xml`: Lightweight Spring Boot service.
- `apps/demo-tools/Dockerfile`: Container configuration for port 8082.
- `apps/demo-tools/src/main/java/com/agentgate/demotools/DemoToolsApplication.java`: Entry point.
- `apps/demo-tools/src/main/java/com/agentgate/demotools/controller/MockOrderController.java`: Mock downstream REST endpoints (`GET /api/orders/{id}`, `POST /api/orders/{id}/refund`).

### Keycloak Realm & Test Automation
- `infra/keycloak/agentgate-realm.json`: Exported Keycloak 26 realm defining:
  - Scopes: `agent:invoke`, `tool:admin`, `delegation:act_as_user`.
  - Clients: `agent-finance-bot`, `agent-support-bot`, `agent-unauthorized-bot`.
  - Service accounts and role assignments (`ADMIN`, `USER`).
- `scripts/smoke-test.ps1` & `scripts/smoke-test.sh`: Multi-container automated health and MCP verification harness.
- `scripts/mcp-client-spike.py`: Independent Python JSON-RPC Streamable HTTP MCP test client.

---

## 3. Observed Behavior & Execution Proof

### 3.1 Maven Build & Unit Tests
- **Command:** `.\mvnw.cmd clean test`
- **Output:**
```text
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Build Order:
[INFO]   AgentGate Parent Aggregator [pom]
[INFO]   AgentGate Core Gateway [jar]
[INFO]   AgentGate Mock Demo Tools [jar]
...
[INFO] Running com.agentgate.gateway.GatewayApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.agentgate.gateway.identity.JwtSecurityIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.agentgate.gateway.mcp.McpConnectivityTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.agentgate.gateway.architecture.ModularMonolithArchitectureTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  31.284 s
```

### 3.2 Docker Compose Health
- **Command:** `docker compose ps`
```text
NAME                   IMAGE                            COMMAND                  SERVICE      STATUS                    PORTS
agentgate-demo-tools   agentgate-demo-tools             "sh -c 'java $JAVA_O…"   demo-tools   Up 4 minutes (healthy)    0.0.0.0:8082->8082/tcp
agentgate-gateway      agentgate-gateway                "sh -c 'java $JAVA_O…"   gateway      Up 26 seconds (healthy)   0.0.0.0:8080->8080/tcp
agentgate-keycloak     quay.io/keycloak/keycloak:26.0   "/opt/keycloak/bin/k…"   keycloak     Up 4 minutes (healthy)    0.0.0.0:8180->8080/tcp
agentgate-postgres     postgres:16-alpine               "docker-entrypoint.s…"   postgres     Up 4 minutes (healthy)    0.0.0.0:5432->5432/tcp
agentgate-redis        redis:7-alpine                   "docker-entrypoint.s…"   redis        Up 4 minutes (healthy)    0.0.0.0:6379->6379/tcp
```

### 3.3 Memory Budget Measurement (C1)
- **Command:** `docker stats --no-stream`
- **Output:**
```text
NAME                   MEM USAGE / LIMIT   MEM %     CPU %
agentgate-gateway      307.4MiB / 1GiB     30.01%    0.21%
agentgate-postgres     41.06MiB / 256MiB   16.04%    0.00%
agentgate-redis        7.648MiB / 128MiB   5.98%     0.42%
agentgate-keycloak     470.8MiB / 768MiB   61.30%    0.24%
agentgate-demo-tools   179.7MiB / 256MiB   70.20%    0.11%
```
**Total Actual Memory Consumed:** ~1,006.6 MiB (~0.98 GB), well within the 2.5 GB hard ceiling.

### 3.4 MCP Connectivity & Protocol Spike (Reviewer Verification)
- **Active Endpoint Property Key:** `spring.ai.mcp.server.streamable-http.mcp-endpoint` (defaulting to `/mcp`)
- **Negotiated Protocol Version:** `2024-11-05` (Streamable HTTP transport)
- **Command:** `python scripts/mcp-client-spike.py`
- **Output:**
```text
======================================================================
AgentGate M01: MCP Streamable HTTP & Security Verification Spike
======================================================================

[Test 1] Unauthenticated MCP invocation (No Token)
  HTTP Status: 401 (Expected: 401)
  PASS: Unauthenticated request rejected with HTTP 401.

[Step] Acquiring Keycloak Tokens...
  Acquired token for agent-support-bot (len=886)
  Acquired token for agent-finance-bot (len=931)
  Acquired token for agent-unauthorized-bot (len=883)

[Test 2] Unauthorized Agent Invocation (Missing agent:invoke scope)
  HTTP Status: 403 (Expected: 403)
  PASS: Unauthorized agent rejected with HTTP 403.

[Test 3] MCP Session Initialize (agent-support-bot)
  HTTP Status: 200
  Response Body: {
    "jsonrpc": "2.0",
    "id": 10,
    "result": {
      "protocolVersion": "2024-11-05",
      "capabilities": {
        "completions": {},
        "logging": {},
        "prompts": { "listChanged": true },
        "resources": { "subscribe": false, "listChanged": true },
        "tools": { "listChanged": true }
      },
      "serverInfo": { "name": "agentgate-mcp-server", "version": "1.0.0" }
    }
  }
  Negotiated Session ID: e73558cd-6e4c-41b6-87f4-1176f9ffa2d1

[Test 4] Tool Discovery (agent-support-bot)
  HTTP Status: 200
  Discovered tool names: ['m01_status', 'm01_restricted_admin_tool']
  PASS: Both test fixture tools successfully discovered.

[Test 5] Permitted Tool Call: m01_status (agent-support-bot)
  HTTP Status: 200
  Tool Output: {
    "jsonrpc": "2.0",
    "id": 12,
    "result": {
      "content": [
        {
          "type": "text",
          "text": "{\"status\":\"HEALTHY\",\"milestone\":\"M01\",\"timestamp\":\"2026-11-14T21:17:26.988900477Z\",\"echo\":\"hello from support bot\",\"callingAgent\":\"agent-support-bot\"}"
        }
      ],
      "isError": false
    }
  }
  PASS: Permitted tool m01_status successfully executed.

[Test 6] Direct Restricted Tool Call WITHOUT Authority (agent-support-bot)
  HTTP Status: 200
  Response: {
    "jsonrpc": "2.0",
    "id": 13,
    "result": {
      "content": [
        {
          "type": "text",
          "text": "Access Denied: Agent 'agent-support-bot' lacks required admin authority for m01_restricted_admin_tool"
        }
      ],
      "isError": true
    }
  }
  PASS: Restricted tool invocation rejected for unprivileged agent.

[Test 7] Direct Restricted Tool Call WITH Authority (agent-finance-bot)
  HTTP Status: 200
  Response: {
    "jsonrpc": "2.0",
    "id": 13,
    "result": {
      "content": [
        {
          "type": "text",
          "text": "{\"status\":\"EXECUTED\",\"authorizedAgent\":\"agent-finance-bot\",\"result\":\"Administrative task completed successfully\",\"action\":\"unauthorized_reset\"}"
        }
      ],
      "isError": false
    }
  }
  PASS: Restricted tool executed successfully for privileged agent.

======================================================================
ALL 7 MCP STREAMABLE HTTP & SECURITY SPIKE TESTS PASSED!
======================================================================
```

### 3.5 Dynamic Tool Listing Assessment (Findings for M02)
- **Spring AI MCP Server Behavior:** In Spring AI 2.0.2, tools registered through `ToolCallbackProvider` are published globally to all connected MCP clients during `tools/list`.
- **Policy Enforcement Locus:** Because `tools/list` currently returns the server-wide tool specifications, tool authorization must be strictly enforced at invocation time (`tools/call`), as demonstrated in Test 6 vs Test 7.
- **Architectural Recommendation for M02:** For Milestone M02 (Dynamic Tool Registry & Metadata Model), AgentGate should implement dynamic tool discovery filtering by wrapping the tool callback resolver or intercepting `tools/list` JSON-RPC requests using an agent-aware proxy layer that queries the PostgreSQL tool registry based on the authenticated agent's allowed scopes.

---

## 4. Requirements Traceability Updates

| Requirement ID | Milestone | Status | Verification Evidence Link |
|---|---|---|---|
| **C1** (Stack & Resource) | M01 | **IMPLEMENTED** | Verified $\le 2.5\text{ GB}$ hard ceiling; actual total container RAM = 1,006.6 MiB |
| **C2** (Configuration) | M01 | **IMPLEMENTED** | `.env.example` provided; typed externalized YAML with profile overlays (`dev`, `test`) |
| **C3** (API Standards) | M01 | **IMPLEMENTED** | RFC 9457 `ProblemDetail` via `GlobalExceptionHandler`; Spring Boot Actuator health endpoints UP |
| **C4** (Observability) | M01 | **IMPLEMENTED** | Logback MDC pattern configured with traceId and spanId; healthchecks on all services |
| **C5** (Testing) | M01 | **IMPLEMENTED** | 10/10 automated tests passing (`mvn clean test`); end-to-end `smoke-test.ps1` passing |
| **C6** (Architecture Rules) | M01 | **IMPLEMENTED** | ArchUnit test `ModularMonolithArchitectureTest` passes with zero cyclic dependencies |
| **FR2** (Agent Identity) | M01 | **IMPLEMENTED (Base)** | Keycloak 26 OIDC token verification with client credentials, `azp`, scopes, and roles |

---

## 5. Errors Observed & Remediated During M01

1. **Spring Boot 4 Test Modularization:**
   - *Error:* `package org.springframework.boot.test.autoconfigure.web.servlet does not exist`
   - *Root Cause:* In Spring Boot 4, test autoconfiguration is modularized into separate starters.
   - *Fix:* Added `spring-boot-starter-webmvc-test` and updated imports to `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
2. **Missing JwtDecoder in Slice Tests:**
   - *Error:* `NoSuchBeanDefinitionException: No qualifying bean of type 'JwtDecoder'`
   - *Root Cause:* `jwk-set-uri` was only populated under the live dev profile.
   - *Fix:* Added a `@ConditionalOnMissingBean` fallback decoder in `SecurityConfig.java` for test environments.
3. **Decoupled Keycloak Hostnames in Docker:**
   - *Error:* Gateway failing token validation due to hostname resolution differences between host and container.
   - *Root Cause:* Keycloak issues tokens with external canonical issuer `http://localhost:8180/realms/agentgate`, while containers resolve JWKS internally via `http://keycloak:8080/...`.
   - *Fix:* Configured `NimbusJwtDecoder` with `jwk-set-uri` pointing internally to `http://keycloak:8080/...` and configured `JwtValidators.createDefaultWithIssuer("http://localhost:8180/realms/agentgate")` to ensure full cryptographic and issuer validation.
4. **Spring AI McpServer Tool Discovery Registration:**
   - *Error:* `SyncMcpToolProvider` logged `No tool methods found in the provided tool objects: []`, resulting in empty `tools: []`.
   - *Root Cause:* Spring AI McpServer requires tools to be exposed via `ToolCallbackProvider`.
   - *Fix:* Created `McpConfig.java` declaring `@Bean public ToolCallbackProvider demoToolsCallbackProvider(DemoToolsMcpProvider provider) { return ToolCallbackProvider.from(ToolCallbacks.from(provider)); }`.
5. **WSL2 Idle VM Shutdown Timeout:**
   - *Error:* Docker containers periodically exited with status 143 when idle.
   - *Root Cause:* Windows WSL2 shuts down the VM when no console or interactive process holds it open.
   - *Fix:* Configured `%USERPROFILE%\.wslconfig` with `[wsl2] vmIdleTimeout=-1` and spawned a persistent keepalive daemon process.

---

## 6. Reviewer Sign-Off Gate

**Reviewer Verdict:** Awaiting External Architecture Reviewer Evaluation  
**Reviewer Notes:** Implementation is complete, all verification steps succeed, and all acceptance criteria have been verified with empirical logs.

---
*End of Milestone M01 Completion Report.*
