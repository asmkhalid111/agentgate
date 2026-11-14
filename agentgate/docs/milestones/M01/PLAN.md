# Milestone M01 Plan: Environment and Secure Application Foundation

**Status:** DRAFT (Awaiting Architecture Reviewer Approval)  
**Milestone:** M01  
**Project:** CSE 444 Project 1 — AgentGate (Governance Gateway for AI Agents' Tool Calls)  
**Author:** Student Implementation Team  
**Reviewer:** External Architecture Reviewer (ChatGPT)  
**Date:** 2026-11-15  
**Primary Coverage:** C1, C2, C3, C4, C5, C6 (Foundational layer for FR2 and MCP connectivity)  

---

## 1. Executive Summary & Gated Operating Mode Declaration

This document constitutes the comprehensive implementation plan for **Milestone M01: Environment and Secure Application Foundation**, authored in strict accordance with the approved [M00 Baseline Architecture](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/milestones/M00/PLAN.md).

In strict compliance with `AGENTS.md` and the project governance contract:
- **This milestone is strictly PLAN ONLY at this stage.**
- **No Java application code, Maven POMs, Docker Compose configurations, or test source files have been generated or modified.**
- **No dependencies have been installed, and no Docker containers have been started.**
- **Implementation will NOT begin until this M01 plan receives an explicit `APPROVED` verdict from the external architecture reviewer (ChatGPT).**

---

## 2. Environment Preparation Checklist & Exact Commands

A pre-flight inventory of the host workstation (`E:\Codes\Software-Architecture-Project`) was performed. The following exact commands are documented for student setup prior to M01 implementation:

| Tool / Runtime | Required Baseline | Current State | Installation / Configuration Command (PowerShell) | Verification Command & Expected Output |
|---|---|---|---|---|
| **OS / Shell** | Windows 10/11, PowerShell 7 | Ready | Native Windows PowerShell 7.5.4 | `$PSVersionTable.PSVersion` $\to$ `7.5.x` |
| **Git** | 2.40+ | **Ready** (`2.55.0`) | Initialized (`e328bb1`, `0691bdc`) | `git --version` $\to$ `git version 2.55.0.windows.3` |
| **Java JDK** | **Java 21 LTS** (Eclipse Adoptium) | *Not on PATH* | `winget install EclipseAdoptium.Temurin.21.JDK` | `java -version` $\to$ `openjdk version "21.0.x"` |
| **Maven** | Maven 3.9+ / Wrapper | *Not on PATH* | Bundled via Maven Wrapper (`mvnw.cmd`) in M01 skeleton; no global install required. | `.\mvnw.cmd -version` $\to$ `Apache Maven 3.9.x` |
| **Docker Desktop** | Docker 24+, Compose v2 | *Not on PATH* | `winget install Docker.DockerDesktop` | `docker --version; docker compose version` $\to$ `Docker version 27.x, Docker Compose version v2.x` |
| **WSL2** | WSL2 (Ubuntu) | Installed | `wsl --status` (WSL 2 default version) | `wsl -l -v` $\to$ `Ubuntu Running 2` |
| **Python** | Python 3.10+ | Installed | Needed for scripted MCP client tests (`scripts/mcp-client-spike.py`) | `python --version` $\to$ `Python 3.11.x` |

> [!NOTE]
> All software installation commands use standard Windows Package Manager (`winget`) and require zero administrative registry hacking. Installations remain pending until user executes them.

---

## 3. Maven Multi-Module Project Structure & Exact File Tree

The repository will be structured as a clean Maven multi-module aggregator rooted in `agentgate/`. No empty placeholder modules for future milestones will be created.

### 3.1 Aggregator and Module Layout
- **Root Aggregator (`pom.xml`):** Declares `<packaging>pom</packaging>`, manages dependencies via Spring Boot 4.0.9 parent and Spring AI BOM 2.0.2, and aggregates `apps/gateway` and `apps/demo-tools`.
- **`apps/gateway`:** The core modular monolith (`<packaging>jar</packaging>`).
- **`apps/demo-tools`:** Mock downstream REST and MCP services (`<packaging>jar</packaging>`).

### 3.2 Exact Proposed File Tree for M01
```text
agentgate/
├── pom.xml                                      # Root aggregator POM (Spring Boot 4.0.9 parent, Spring AI BOM)
├── mvnw                                         # Maven wrapper Unix script
├── mvnw.cmd                                     # Maven wrapper Windows batch script
├── .mvn/
│   └── wrapper/
│       ├── maven-wrapper.jar
│       └── maven-wrapper.properties             # Distribution URL for Apache Maven 3.9.9
├── compose.yaml                                 # Multi-container Compose (Gateway, Keycloak, Postgres, Redis, Demo-Tools)
├── .env.example                                 # Environment placeholders (safe development defaults)
├── .gitignore                                   # Java, Maven, IDE, OS, container ignores
├── apps/
│   ├── gateway/
│   │   ├── pom.xml                              # Gateway module POM
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/com/agentgate/gateway/
│   │       │   │   ├── GatewayApplication.java  # Main Spring Boot entry point
│   │       │   │   ├── config/
│   │       │   │   │   ├── SecurityConfig.java  # OAuth2 Resource Server, JWT converter, CSRF
│   │       │   │   │   └── McpServerConfig.java # Streamable HTTP MCP configuration
│   │       │   │   ├── identity/
│   │       │   │   │   ├── AgentIdentity.java   # Record: clientId, scopes, roles
│   │       │   │   │   ├── UserContext.java     # Record: userId, azp, delegationScope
│   │       │   │   │   ├── JwtAgentAuthenticationConverter.java # Extracts identity from Bearer token
│   │       │   │   │   └── AgentContextHolder.java # Accessor for SecurityContextHolder
│   │       │   │   ├── mcp/
│   │       │   │   │   ├── McpSecurityInterceptor.java # Intercepts JSON-RPC, enforces token & tool authorization
│   │       │   │   │   └── DemoToolsMcpProvider.java   # M01 static test tool definitions for connectivity spike
│   │       │   │   └── common/
│   │       │   │       ├── GlobalExceptionHandler.java # RFC 9457 ProblemDetail response for auth/validation errors
│   │       │   │       └── ErrorCodes.java      # Standardized gateway error constants
│   │       │   └── resources/
│   │       │       ├── application.yml          # Base config: virtual threads, Actuator, HikariCP, logging
│   │       │       ├── application-dev.yml      # Dev profile: Keycloak JWKS, Postgres, Redis, Streamable MCP
│   │       │       ├── application-test.yml     # Test profile: in-memory/mock security slices
│   │       │       └── db/migration/
│   │       │           └── V1__m01_baseline_schema.sql # Flyway initial DDL (baseline tables & marker)
│   │       └── test/
│   │           ├── java/com/agentgate/gateway/
│   │           │   ├── GatewayApplicationTests.java # Full Spring application context load test
│   │           │   ├── architecture/
│   │           │   │   └── ModularMonolithArchitectureTest.java # ArchUnit package boundary rules
│   │           │   ├── identity/
│   │           │   │   └── JwtSecurityIntegrationTest.java     # Security slice: valid, expired, missing scopes
│   │           │   └── mcp/
│   │           │       └── McpConnectivityTest.java            # WebMvc slice: Streamable HTTP handshake & auth
│   │           └── resources/
│   │               └── application-test.yml
│   └── demo-tools/
│       ├── pom.xml                              # Mock tools module POM
│       └── src/
│           ├── main/
│           │   ├── java/com/agentgate/demotools/
│           │   │   ├── DemoToolsApplication.java # Mock service main class
│           │   │   └── controller/
│           │   │       └── MockOrderController.java # Endpoints: GET /api/orders/{id}, POST /api/orders/{id}/refund
│           │   └── resources/
│           │       └── application.yml          # Server port 8082
│           └── test/
│               └── java/com/agentgate/demotools/
│                   └── DemoToolsApplicationTests.java
├── infra/
│   └── keycloak/
│       └── agentgate-realm.json                 # Pre-configured test realm, clients, scopes, and test users
├── scripts/
│   ├── mcp-client-spike.py                     # Independent Python MCP client for Streamable HTTP spike
│   ├── smoke-test.ps1                          # Windows PowerShell health check script
│   └── smoke-test.sh                           # Bash health check script
└── docs/
    └── milestones/
        └── M01/
            └── PLAN.md                          # THIS APPROVED DOCUMENT
```

---

## 4. Infrastructure & Single-Command Docker Compose (8GB Laptop Budget)

### 4.1 Multi-Container Compose Configuration (`compose.yaml`)
A single command (`docker compose up --build -d`) will start all five interconnected services with strict memory limits:

```yaml
services:
  gateway:
    build:
      context: .
      dockerfile: apps/gateway/Dockerfile
    container_name: agentgate-gateway
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/agentgate_db
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-agentgate}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-agentgate_dev_password}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/agentgate
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      keycloak:
        condition: service_healthy
    mem_limit: 1024m
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  demo-tools:
    build:
      context: .
      dockerfile: apps/demo-tools/Dockerfile
    container_name: agentgate-demo-tools
    ports:
      - "8082:8082"
    mem_limit: 256m
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8082/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres:
    image: postgres:16-alpine
    container_name: agentgate-postgres
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: agentgate_db
      POSTGRES_USER: ${POSTGRES_USER:-agentgate}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-agentgate_dev_password}
    volumes:
      - agentgate-pgdata:/var/lib/postgresql/data
    mem_limit: 256m
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U agentgate -d agentgate_db"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: agentgate-redis
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes", "--appendfsync", "everysec"]
    volumes:
      - agentgate-redisdata:/data
    mem_limit: 128m
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    container_name: agentgate-keycloak
    ports:
      - "8180:8080"
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: ${KEYCLOAK_ADMIN:-admin}
      KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin_dev_password}
      KC_HTTP_RELATIVE_PATH: /
    volumes:
      - ./infra/keycloak:/opt/keycloak/data/import
    mem_limit: 768m
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080 && echo -e 'GET /realms/agentgate/.well-known/openid-configuration HTTP/1.1\\r\\nHost: localhost:8080\\r\\nConnection: close\\r\\n\\r\\n' >&3 && cat <&3 | grep '200 OK' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10

volumes:
  agentgate-pgdata:
  agentgate-redisdata:
```

### 4.2 Memory Budget Breakdown
- `gateway`: 1024MB (JVM `-Xmx512m`)
- `keycloak`: 768MB (JVM `-Xmx384m`)
- `postgres`: 256MB
- `redis`: 128MB
- `demo-tools`: 256MB (JVM `-Xmx128m`)
- **Total Combined Allocation:** $2432\text{ MB} \approx 2.37\text{ GB}$, leaving $>5.5\text{ GB}$ free for the host operating system on an 8GB laptop.

---

## 5. Minimal Spring Boot Application Design (M01 Focus)

### 5.1 Concurrency & Runtime Configuration
- **Virtual Threads:** Enabled via standard Spring Boot 4 property:
  ```yaml
  spring:
    threads:
      virtual:
        enabled: true
  ```
- **HikariCP Pool:** Configured to 10 connections for M01 dev profile:
  ```yaml
  spring:
    datasource:
      hikari:
        maximum-pool-size: 10
        minimum-idle: 2
  ```

### 5.2 Spring Security OAuth2 Resource Server & Anti-Spoofing
- **Token Verification:**
  - Validates RSA-signed JWTs from Keycloak (`http://localhost:8180/realms/agentgate/protocol/openid-connect/certs`).
  - Custom `JwtAgentAuthenticationConverter` extracts:
    - `clientId`: extracted from `azp` or `client_id` claims.
    - `scopes`: mapped from `scope` claim (`SCOPE_agent:invoke`, `SCOPE_delegation:act_as_user`).
    - `roles`: mapped from `realm_access.roles` (`ROLE_ADMIN`, `ROLE_APPROVER`).
- **Endpoint Authorization Matrix:**
  ```java
  http
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
          .requestMatchers("/mcp/**").hasAuthority("SCOPE_agent:invoke")
          .anyRequest().denyAll()
      )
      .oauth2ResourceServer(oauth2 -> oauth2
          .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAgentAuthenticationConverter))
      )
      .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp/**"));
  ```
- **Anti-Spoofing Rule:** Unsigned HTTP headers (`X-User-Id`) are rejected. If an end-user assertion is presented in `X-User-Assertion`, it is validated against Keycloak JWKS and its `azp` claim is verified to match the calling agent's `clientId`.

### 5.3 Flyway Minimal Baseline Schema (`V1__m01_baseline_schema.sql`)
```sql
-- Initial Flyway baseline for Milestone M01
CREATE TABLE IF NOT EXISTS agent_client (
    id VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS m01_schema_marker (
    installed_on TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    milestone VARCHAR(16) NOT NULL DEFAULT 'M01',
    status VARCHAR(32) NOT NULL DEFAULT 'INITIALIZED'
);

INSERT INTO agent_client (id, client_id, display_name, active)
VALUES ('agent-finance-01', 'agent-finance-bot', 'Finance Automation Bot', true)
ON CONFLICT DO NOTHING;
```

### 5.4 Structured JSON Logging & Observability (C4)
- Logback structured configuration outputting:
  ```json
  {
    "timestamp": "2026-11-15T02:30:00.123Z",
    "level": "INFO",
    "traceId": "64a7f012b3c4d5e6",
    "spanId": "b3c4d5e6",
    "agentId": "agent-finance-bot",
    "logger": "com.agentgate.gateway.mcp.McpSecurityInterceptor",
    "message": "Authorized tool call: lookup_order"
  }
  ```
- Trace context is auto-propagated via Micrometer Tracing.

---

## 6. MCP Integration & Compatibility Spike (M01 Spike 2)

### 6.1 Starter Coordinates & Property Verification
- **Starter Coordinates:**
  ```xml
  <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
  </dependency>
  <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-mcp-client</artifactId>
  </dependency>
  ```
- **Configuration Property Check:**
  The reviewer specifically cautioned to verify the exact property for the Streamable HTTP endpoint:
  ```yaml
  spring:
    ai:
      mcp:
        server:
          protocol: STREAMABLE
          streamable-http:
            mcp-endpoint: /mcp # Tested during Spike 2 against spring.ai.mcp.server.streamable-http.path
  ```
  Spike 2 will verify whether `streamable-http.mcp-endpoint` or `streamable-http.path` is active in Spring AI 2.0.2 and report the exact working key in `COMPLETION.md`.

### 6.2 Minimal Independent Scripted MCP Client Spike (`scripts/mcp-client-spike.py`)
A standalone Python script using standard `requests` / `httpx` will test the running gateway:
1. **Test Case 1 (Protocol Handshake):** Connects to `POST /mcp` sending standard MCP JSON-RPC `initialize` message. Captures negotiated protocol version (`2025-03-26` / `2026-07-28`).
2. **Test Case 2 (Bearer Authentication):** Obtains OAuth2 token from Keycloak for `agent-finance-bot`; connects with `Authorization: Bearer <jwt>`. Asserts HTTP 200.
3. **Test Case 3 (Anonymous Rejection):** Connects to `POST /mcp` without token. Asserts HTTP 401 Unauthorized.
4. **Test Case 4 (Permitted Tool Call):** Sends `tools/call` for `m01_status` with valid arguments. Asserts valid JSON-RPC `result` payload.
5. **Test Case 5 (Unauthorized Tool Call):** Sends `tools/call` for `unauthorized_admin_tool`. Asserts JSON-RPC protocol error `-32001` or `-32601`.

### 6.3 Assessment of Dynamic Per-Agent `tools/list` Filtering
- **Technical Question:** Can Spring AI's official MCP server starter dynamically filter the list returned by `tools/list` on a per-request basis using the caller's OAuth2 token from `SecurityContextHolder`?
- **Spike Procedure:**
  - Investigate `ToolCallbackProvider` and Spring AI's internal `McpServer` request handler.
  - Test if inspecting `SecurityContextHolder.getContext().getAuthentication()` inside a custom `ToolCallbackProvider` dynamically returns a subset of tools.
  - **Reviewer Escalation Protocol:** If Spring AI's starter strictly caches the tool list globally at startup and prevents per-request dynamic projection, the team will document the exact internal mechanism in `docs/milestones/M01/COMPLETION.md` and propose a sanctioned integration approach for reviewer approval before M02, rather than silently writing a custom transport.
- For M01, static test-fixture tools are used strictly to demonstrate authenticated connectivity. Full dynamic registry features belong to **M02**.

---

## 7. Testing, Verification & Acceptance Criteria

### 7.1 Verification Test Matrix

| Layer / Component | Test Class / Script | What is Verified | Pass Criterion |
|---|---|---|---|
| **Context & Build** | `GatewayApplicationTests` | Spring context loads with all configurations active. | Build succeeds without error; context healthy in $<10\text{ s}$. |
| **Architecture (C6)** | `ModularMonolithArchitectureTest` | ArchUnit tests verify no package leaks or prohibited cross-dependencies. | 100% of ArchUnit rules pass. |
| **Security (FR2)** | `JwtSecurityIntegrationTest` | MockMvc slice test verifying valid JWT, invalid signature, expired token, and missing scopes. | Valid token returns 200; missing/expired returns 401; missing scope returns 403. |
| **MCP Spike** | `McpConnectivityTest` & `scripts/mcp-client-spike.py` | Streamable HTTP handshake, JSON-RPC envelopes, auth enforcement, tool execution. | Script exits code 0; all 5 spike tests pass. |
| **Infrastructure (C1)**| `scripts/smoke-test.ps1` | Docker Compose health check across all 5 containers. | All 5 containers report `healthy` in $<90\text{ s}$. |
| **Memory Budget (C1)**| `docker stats --no-stream` | Measured physical RAM footprint under steady-state idle and spike load. | Combined container memory $\le 2.5\text{ GB}$; host retains $>5\text{ GB}$ free. |

### 7.2 Exact Execution Commands for Antigravity Verification
1. **Compilation & Unit Testing:**
   ```powershell
   .\mvnw.cmd clean test
   ```
2. **Docker Compose Startup:**
   ```powershell
   docker compose up --build -d
   ```
3. **Smoke Test Execution:**
   ```powershell
   powershell -File .\scripts\smoke-test.ps1
   ```
4. **Independent MCP Client Spike:**
   ```powershell
   python .\scripts\mcp-client-spike.py
   ```
5. **Memory Measurement:**
   ```powershell
   docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"
   ```
6. **Teardown:**
   ```powershell
   docker compose down -v
   ```

---

## 8. Preserved Architecture & Strict Scope Boundaries

To prevent milestone creep, Milestone M01 is strictly bounded:

### 8.1 Strictly In-Scope for M01
- Aggregator POM and Maven Wrapper.
- Docker Compose multi-container setup (Gateway, Demo-tools, Keycloak, Postgres, Redis).
- Basic Spring Boot 4.0.9 application skeleton with virtual threads.
- Spring Security OAuth2 resource server with Keycloak JWT validation.
- Flyway V1 baseline DDL.
- Spring AI MCP starter configuration with Streamable HTTP on `/mcp`.
- M01 MCP connectivity spike script.
- Actuator health check and structured logging.
- ArchUnit architecture enforcement test.

### 8.2 Strictly Excluded from M01 (Deferred to Future Milestones)
- **Full Tool Registry & OpenAPI Import:** $\to$ **M02**.
- **Policy Engine, Versioning & Playground:** $\to$ **M03**.
- **Schema Guards & PII Redaction:** $\to$ **M04**.
- **Human Approval State Machine:** $\to$ **M05**.
- **Redis Sliding Window Quotas & Emergency Kill Switches:** $\to$ **M06**.
- **Tamper-Evident Audit Hash Chain & Local WAL Buffer:** $\to$ **M07**.
- **Downstream Resilience & Circuit Breakers:** $\to$ **M08**.
- **Admin Web Portal & Adoption Workflows:** $\to$ **M09 / M10**.
- **Checkpoint Change Request:** $\to$ **M-CR**.

---

## 9. Anticipated Failure Modes & Contingency Plans (M01 Register)

| ID | Anticipated Risk / Failure Mode | Root Cause / Mechanism | Preventive Test in M01 | Contingency Plan |
|---|---|---|---|---|
| **R01-01** | Dependency convergence error between Spring Boot 4.0.9 and Spring AI BOM 2.0.2. | Upstream version mismatch or missing transitives in new releases. | Maven build (`.\mvnw.cmd test-compile`). | Explicitly declare transitive dependency versions in aggregator `<dependencyManagement>` to force convergence. |
| **R01-02** | Streamable HTTP endpoint configuration property unrecognized. | Spring AI 2.0.2 property key differs from documentation (`streamable-http.path` vs `mcp-endpoint`). | `McpConnectivityTest` slice test. | Spike 2 will test both keys via `@TestPropertySource` and lock the verified key in `application.yml`. |
| **R01-03** | Keycloak container takes $>60\text{ s}$ to import realm, delaying gateway startup. | Keycloak startup overhead on Windows Docker. | Docker Compose `depends_on` with `service_healthy`. | Tune Keycloak health check interval and pre-warm container; health check ensures gateway waits until realm is ready. |
| **R01-04** | Spring AI MCP starter rejects custom dynamic per-identity tool filtering. | Starter internals assume static `@McpTool` beans registered globally. | Spike 2 dynamic filtering probe. | Document exact internal limitation in `M01/COMPLETION.md` and submit proposed adapter for reviewer approval in M02. |
| **R01-05** | Total Docker Compose RAM exceeds 2.5 GB target on startup. | Keycloak or JVM initial heap over-allocating on container boot. | `docker stats --no-stream` check. | Tune JVM flags: add `-XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC` for demo-tools and gateway to constrain footprint. |

---

## 10. Milestone M01 Acceptance Criteria & Reviewer Sign-Off Gate

### 10.1 M01 Exit Criteria (Definition of Done)
1. **Clean Maven Build:** `.\mvnw.cmd clean test` executes with 0 compilation errors and 100% passing tests.
2. **ArchUnit Compliance:** Architecture tests pass with zero package boundary violations.
3. **Single-Command Startup:** `docker compose up --build -d` starts all 5 containers into healthy status in $<90\text{ s}$.
4. **Security Enforcement:** Unauthenticated requests to `/mcp` receive HTTP 401; requests with valid Keycloak Bearer token pass authentication.
5. **MCP Connectivity Spike:** Independent Python script `scripts/mcp-client-spike.py` successfully executes handshake, auth, permitted tool call, and unauthorized tool rejection.
6. **Memory Budget Verified:** `docker stats` confirms combined container RAM $\le 2.5\text{ GB}$.
7. **Traceability:** Detailed `docs/milestones/M01/COMPLETION.md` authored with raw logs, command outputs, and Git commit references.

### 10.2 Architectural Decisions Submitted for Reviewer Approval
1. **Approval of Multi-Module POM & Skeleton Structure:** Concurrence with the exact proposed file layout (`apps/gateway`, `apps/demo-tools`, `infra/keycloak`, `scripts`).
2. **Approval of M01 Narrow Scope:** Confirmation that postponing full registry, policy engine, approvals, quotas, and audit WAL to later milestones is approved.
3. **Approval of MCP Spike Strategy:** Concurrence with evaluating Spring AI MCP dynamic listing via Spike 2 before committing to M02 registry design.
4. **Formal Approval to Begin M01 Implementation:** Issuance of an official verdict of **`APPROVED`** to commence writing code, POMs, and Compose files for Milestone M01.

---
*End of Milestone M01 Implementation Plan.*
