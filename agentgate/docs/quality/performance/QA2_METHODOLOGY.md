# AgentGate QA2 Performance Benchmarking Methodology

## 1. Overview & Authoritative Requirement

This document defines the rigorous, evidence-grounded performance benchmarking methodology for **QA2: Gateway Latency Under Load** in the AgentGate modular monolith architecture.

The authoritative requirement from `docs/governance/SOURCE_REQUIREMENTS.md` specifies:

> *"The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."*

### Key Clarifications & Definitions
- **Excluding approval waits:** The benchmark measures direct tool execution (`DIRECT_EXECUTION`) that does not require human-in-the-loop intervention (low-risk read operations), ensuring approval turnaround time does not contaminate latency metrics.
- **Sustained Arrival Rate:** Constant arrival rate of **100 calls per second** ($\pm 2\%$) generated via an open-loop dispatcher to prevent coordinated omission.
- **Multi-Run Verification:** Three distinct, consecutive steady-state runs of 60 seconds each ($3 \times 6,000 = 18,000$ calls) following a 30-second warm-up phase.

---

## 2. Metric Formulations

### 2.1 Primary Metric: Per-Request Incremental Gateway Overhead

To eliminate network jitter and client-side scheduling variability, the primary benchmark metric measures the internal incremental overhead added by the gateway pipeline for each request $i$:

$$\text{overhead}_i = \text{gateway\_duration}_i - \text{downstream\_duration}_i$$

Where:
- $\text{gateway\_duration}_i$: High-resolution monotonic elapsed time ($t_{\text{egress}} - t_{\text{ingress}}$) recorded by `BenchmarkTimingFilter` via `System.nanoTime()`.
- $\text{downstream\_duration}_i$: High-resolution monotonic duration recorded by `RestToolAdapter` specifically wrapping the downstream HTTP request to `demo-tools`.
- $\text{overhead}_i$: Net duration consumed by gateway security and governance layers:
  1. OAuth2 Bearer JWT extraction and cryptographic signature validation (cached JWKS).
  2. FR8 Emergency Kill-Switch evaluation.
  3. FR7 Tool-Agent permission matrix verification.
  4. FR9 Redis Lua token-bucket rate-limiting and quota checking.
  5. FR6 JSON Schema validation against registered input schema.
  6. FR4 Structured policy evaluation (rule matching and condition checking).
  7. Downstream protocol adaptation.
  8. FR5 Context-sensitive PII redaction and payload truncation.
  9. FR2 Cryptographic SHA-256 tamper-evident audit record generation and hash-chain linkage.

The 95th percentile ($p95$) is calculated from the pooled distribution of all $\text{overhead}_i$ values across the 18,000 steady-state calls:

$$p95(\text{overhead}) \le 30.0\text{ ms}$$

### 2.2 Secondary Metric: Client End-to-End Latency Delta

As an external validation metric, the client records end-to-end round-trip latency for both direct downstream calls ($D_{\text{direct}}$) and governed MCP gateway calls ($D_{\text{governed}}$):

$$\Delta L_{\text{client}} = p95(D_{\text{governed}}) - p95(D_{\text{direct}})$$

---

## 3. Architecture & Test Isolation

To avoid contaminating production or development database state, the benchmark executes against an isolated, reproducible Docker Compose environment:

```
[ Load Generator: Python Open-Loop Harness (20 persistent connections) ]
                       |
        +--------------+---------------+
        |                              |
 (Direct Baseline:              (Governed MCP:
  http://localhost:8083)         http://localhost:8081/mcp)
        |                              |
        v                              v
[ benchmark-demo-tools ]     [ benchmark-gateway ]
   (Port 8083:8082)             (Port 8081:8080)
                                  |    |    |
     +----------------------------+    |    +----------------------------+
     |                                 |                                 |
     v                                 v                                 v
[ benchmark-postgres ]       [ benchmark-redis ]             [ agentgate-keycloak ]
 (Port 5433:5432, 256MB)      (Port 6380:6379, 64MB)          (Port 8180:8080, Shared)
 (Dedicated Data Volume)      (In-memory, appendonly=no)      (Stateless JWT / JWKS)
```

### Resource Constraints & Allocations
- **Host OS:** Windows 11 Pro, 16 GB RAM, Intel Core i5 processor.
- **Docker Engine:** Docker Desktop 28.x with WSL2 Linux backend.
- **`benchmark-gateway`:** 512 MB memory limit, 1 vCPU equivalent.
- **`benchmark-postgres`:** 256 MB memory limit, dedicated Docker volume `agentgate_benchmark_pgdata`.
- **`benchmark-redis`:** 64 MB memory limit, in-memory mode (`--appendonly no`).
- **`benchmark-demo-tools`:** 192 MB memory limit.
- **Connection Management:** Keep-alive HTTP/1.1 connection pool with 20 persistent worker sockets.

---

## 4. Workload Protocol

| Phase | Target Endpoint | Concurrency | Target Rate | Duration | Total Calls | Purpose |
|---|---|---|---|---|---|---|
| **Phase 1: Warmup** | Gateway `/mcp` | 20 workers | 50 RPS | 30 s | 1,500 | JIT compilation (C2), HikariCP pool warm-up, Redis pool warm-up, JWKS cache population. Discarded from evaluation. |
| **Phase 2: Direct Baseline** | Demo-Tools `/api/orders/{id}` | 20 workers | 100 RPS | 60 s | 6,000 | Establishes direct downstream latency without gateway governance. |
| **Phase 3: Governed Run 1** | Gateway `/mcp` | 20 workers | 100 RPS | 60 s | 6,000 | First steady-state governed execution. |
| **Phase 4: Governed Run 2** | Gateway `/mcp` | 20 workers | 100 RPS | 60 s | 6,000 | Second steady-state governed execution. |
| **Phase 5: Governed Run 3** | Gateway `/mcp` | 20 workers | 100 RPS | 60 s | 6,000 | Third steady-state governed execution. |
| **Phase 6: Audit Verification** | Gateway `/api/v1/admin/audit/verify` | 1 client | - | - | - | Complete cryptographic verification of SHA-256 hash chain across all recorded audit entries. |

---

## 5. Timing Instrumentation Mechanics

1. **Gateway Ingress/Egress (`BenchmarkTimingFilter`):**
   - Implemented as a Servlet `OncePerRequestFilter` ordered at `Ordered.HIGHEST_PRECEDENCE`.
   - Feature-flagged via `agentgate.benchmark.timing.enabled=true` (default: `false` in development/production).
   - Ingress captures `long startNs = System.nanoTime();`.
   - Generates/extracts `X-Request-Correlation-Id`.
   - Attaches response headers:
     - `X-Gateway-Duration-Ns`: Total server time in nanoseconds.
     - `X-Downstream-Duration-Ns`: Sub-adapter duration in nanoseconds.
     - `X-Gateway-Overhead-Ns`: Net overhead in nanoseconds.
     - `X-Request-Correlation-Id`: Tracing correlation identifier.
2. **Downstream Execution (`RestToolAdapter`):**
   - High-resolution timing captures `long startNs = System.nanoTime();` immediately prior to Spring `RestClient.retrieve().body(String.class)` and records duration to `BenchmarkTimingContext`.
3. **MCP Streamable Transport Binding (`DynamicGovernedToolCallback`):**
   - Directly sets timing headers on `HttpServletResponse` prior to JSON-RPC serialization, ensuring headers are committed before HTTP chunk streaming occurs.

---

## 6. Audit Chain Integrity Verification

Post-benchmark verification invokes `POST /api/v1/admin/audit/verify` using `human-admin` credentials. The verification engine:
1. Verifies Genesis hash initialization (`0000...0000`).
2. Iterates across all audit rows in sequence, confirming no missing sequence numbers.
3. Confirms each row's `prev_record_hash` matches the preceding row's `record_hash`.
4. Recomputes canonical SHA-256 for each row and verifies hash equality.
5. Verifies concordance with `audit_chain_state` tip.

---

## 7. Reproduction Steps

To execute the benchmark reproducibly:

```powershell
# 1. Package the gateway artifact
.\mvnw.cmd package -pl apps/gateway -DskipTests

# 2. Launch the isolated benchmark stack
$env:PATH = "C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
docker compose -f compose.benchmark.yaml up --build -d

# 3. Register benchmark tool, permission and policy
docker exec -i agentgate-benchmark-postgres psql -U agentgate -d agentgate_db -c "
INSERT INTO registered_tools (id, name, description, input_schema, risk_level, owning_team, endpoint_url, protocol_type, http_method, enabled, created_at, updated_at)
VALUES ('tool-benchmark-read', 'benchmark_read', 'Benchmark read tool', '{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}', 'LOW', 'Benchmark Team', 'http://benchmark-demo-tools:8082/api/orders/{id}', 'REST', 'GET', true, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET endpoint_url = EXCLUDED.endpoint_url;

INSERT INTO tool_agent_permissions (tool_name, agent_id)
VALUES ('benchmark_read', 'agent-support-bot')
ON CONFLICT DO NOTHING;

INSERT INTO policies (id, name, agent_pattern, tool_pattern, conditions_json, action, dry_run, priority, version, enabled, updated_at)
VALUES ('pol-benchmark-read', 'Benchmark Read Allow Policy', '*', 'benchmark_read', '[]', 'ALLOW', false, 10, 1, true, NOW())
ON CONFLICT (id) DO UPDATE SET updated_at = NOW();
"

# 4. Restart benchmark gateway to load registered tool
docker compose -f compose.benchmark.yaml restart benchmark-gateway

# 5. Run benchmark load harness
python scripts/benchmarks/benchmark_qa2_load.py

# 6. Generate charts
python scripts/benchmarks/generate_qa2_charts.py

# 7. Teardown benchmark stack
docker compose -f compose.benchmark.yaml down -v
```
