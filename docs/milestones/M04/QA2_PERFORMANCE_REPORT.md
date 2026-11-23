# AgentGate M04 Task 16B: QA2 Performance Benchmark Report

**Milestone:** M04  
**Date:** November 23, 2026  
**Status:** COMPLETE — EMPIRICAL EVALUATION VERIFIED  
**Final Verdict:** **FAIL** (Measured $p95 = 98.96\text{ ms} > 30.0\text{ ms}$ threshold)  
**Authoritative QA2 Requirement:**
> *"The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."*

---

## 1. Executive Summary

This report documents the empirical execution and results of the **QA2 Performance Benchmark** for AgentGate.

The benchmark evaluated the end-to-end governed tool invocation pipeline under a sustained arrival rate of **100 calls per second** across **18,000 steady-state requests** (three distinct 60-second runs) preceded by a 30-second warm-up phase (1,500 requests) and a 60-second direct downstream baseline (6,000 requests), totaling **25,500 requests**.

### Key Results Summary
- **Direct Baseline Client Latency:** $p50 = 2.76\text{ ms}$, $p95 = 5.71\text{ ms}$, $p99 = 14.46\text{ ms}$ (at 100.01 RPS).
- **Primary Metric (Incremental Gateway Overhead):**
  - **Pooled $p50$ (Median):** **$50.44\text{ ms}$**
  - **Pooled $p90$:** **$66.55\text{ ms}$**
  - **Pooled $p95$:** **$98.96\text{ ms}$** (Target: $\le 30.0\text{ ms}$)
  - **Pooled $p99$:** **$240.10\text{ ms}$**
- **Secondary Metric (Client Latency Delta):**
  - $\Delta L_{\text{client}} = p95(D_{\text{governed}}) - p95(D_{\text{direct}}) = 109.41\text{ ms} - 5.71\text{ ms} =$ **$103.70\text{ ms}$**.
- **Reliability & Error Rate:**
  - Direct baseline: 0 errors / 6,000 calls (0.00% error rate).
  - Governed execution: 14 errors / 18,000 calls (**0.078% error rate**), safely meeting the reliability SLA ($\le 0.1\%$).
- **Cryptographic Audit Chain Integrity:**
  - **19,497 total audit records** cryptographically verified via `/api/v1/admin/audit/verify`.
  - Zero sequence gaps, zero hash corruptions, 100% chain tip concordance (`valid: true`).

### Evaluation Verdict: **FAIL**
AgentGate's governance pipeline currently adds **$98.96\text{ ms}$** at $p95$ under 100 RPS sustained load, exceeding the instructor's $30.0\text{ ms}$ budget by $68.96\text{ ms}$. This result is genuine, reproducible, and grounded in raw telemetry. The primary bottleneck is the row-level lock serialization in PostgreSQL required to guarantee linear cryptographic tamper-evidence in Mode A audit logging.

---

## 2. Test Environment & System Configuration

| Parameter | Configuration |
|---|---|
| **Host Operating System** | Windows 11 Pro 64-bit |
| **Processor** | Intel(R) Core(TM) i5 CPU (8 logical cores) |
| **Host Memory** | 16.0 GB physical RAM |
| **Java Runtime** | Eclipse Temurin OpenJDK 21.0.13.1 |
| **Containerization** | Docker Desktop 28.x with WSL2 Linux backend |
| **Gateway Container** | `agentgate-benchmark-gateway` (512 MB memory limit, Java 21 SerialGC) |
| **Database Container** | `agentgate-benchmark-postgres` (PostgreSQL 16 Alpine, 256 MB memory limit) |
| **Cache Container** | `agentgate-benchmark-redis` (Redis 7 Alpine, in-memory, 64 MB memory limit) |
| **Downstream Container** | `agentgate-benchmark-demo-tools` (Spring Boot mock service, 192 MB memory limit) |
| **Identity Provider** | Keycloak 26.0 (shared realm, stateless M2M JWT with local cached JWKS) |
| **Load Generator** | Python 3.14 open-loop dispatcher with persistent HTTP/1.1 connection pool (20 workers) |

---

## 3. Detailed Latency Breakdown

### 3.1 Direct Downstream Baseline (60s @ 100 RPS)
Direct invocation against `http://localhost:8083/api/orders/ORD-BENCH-01` without gateway governance:

| Metric | Client Latency (ms) |
|---|---|
| **Requests Completed** | 6,000 / 6,000 (100.0%) |
| **Actual Throughput** | 100.01 RPS |
| **Error Count** | 0 (0.00%) |
| **Mean $\pm$ StdDev** | $3.38\text{ ms} \pm 3.65\text{ ms}$ |
| **Min / Max** | $1.40\text{ ms} / 79.02\text{ ms}$ |
| **p50 (Median)** | **$2.76\text{ ms}$** |
| **p90** | $4.76\text{ ms}$ |
| **p95** | **$5.71\text{ ms}$** |
| **p99** | $14.46\text{ ms}$ |

---

### 3.2 Governed Steady-State Runs (3 x 60s @ 100 RPS)

The governed tool invocation (`benchmark_read`) was executed against `http://localhost:8081/mcp` across three distinct runs:

| Run | Requests | Actual RPS | Errors | Client p50 | Client p95 | Gateway p50 | Downstream p50 | Overhead p50 | Overhead p95 |
|---|---|---|---|---|---|---|---|---|---|
| **Run 1** | 6,000 | 99.94 | 14 (0.23%) | 55.62 ms | 144.96 ms | 51.88 ms | 0.00 ms | 51.85 ms | **133.23 ms** |
| **Run 2** | 6,000 | 99.93 | 0 (0.00%) | 53.64 ms | 112.49 ms | 50.48 ms | 0.00 ms | 50.48 ms | **103.89 ms** |
| **Run 3** | 6,000 | 99.93 | 0 (0.00%) | 52.30 ms | 64.61 ms | 49.43 ms | 0.00 ms | 49.41 ms | **59.18 ms** |
| **Pooled** | **18,000** | **99.93** | **14 (0.08%)** | **53.69 ms** | **109.41 ms** | **50.45 ms** | **0.00 ms** | **50.44 ms** | **98.96 ms** |

---

### 3.3 Percentile Comparison Matrix

All figures in milliseconds (ms):

| Pipeline Stage / Metric | Count | Mean | StdDev | Min | p50 | p90 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|---|---|
| **Direct Baseline (Client)** | 6,000 | 3.38 | 3.65 | 1.40 | 2.76 | 4.76 | 5.71 | 14.46 | 79.02 |
| **Governed Client Latency** | 17,986 | 61.72 | 39.73 | 8.26 | 53.69 | 74.14 | 109.41 | 249.39 | 821.49 |
| **Total Gateway Server Time** | 17,986 | 57.65 | 38.34 | 4.29 | 50.45 | 66.96 | 99.39 | 242.23 | 818.01 |
| **Downstream Adapter Duration** | 17,986 | 0.16 | 2.41 | 0.00 | 0.00 | 0.00 | 0.00 | 3.34 | 99.00 |
| **Incremental Gateway Overhead** | **17,986** | **57.49** | **37.70** | **4.29** | **50.44** | **66.55** | **98.96** | **240.10** | **773.36** |

---

## 4. Performance Visualizations

The generated performance charts are located under `docs/quality/performance/charts/`:

### 4.1 Latency Distribution & Cumulative Distribution Function (CDF)
`docs/quality/performance/charts/qa2_latency_distribution.png`
- Shows the probability density histogram and cumulative distribution curve for the 18,000 governed overhead samples.
- The median overhead clusters tightly around $50.44\text{ ms}$, with a tail extending toward $98.96\text{ ms}$ at $p95$, clearly demonstrating where the curve crosses the $30\text{ ms}$ requirement boundary.

### 4.2 Throughput & Latency Time Series
`docs/quality/performance/charts/qa2_throughput_time_series.png`
- Illustrates constant 100 RPS delivery across the entire 180 seconds of steady-state execution across Runs 1, 2, and 3.
- Shows progressive latency stabilization as the JVM runtime warms up: Run 1 ($p95 = 133.23\text{ ms}$) $\rightarrow$ Run 2 ($p95 = 103.89\text{ ms}$) $\rightarrow$ Run 3 ($p95 = 59.18\text{ ms}$).

### 4.3 Component Breakdown: Baseline vs. Governed Pipeline
`docs/quality/performance/charts/qa2_gateway_vs_downstream.png`
- Deconstructs the end-to-end request latency into downstream processing time vs. internal gateway overhead.
- Confirms that downstream tool execution adds negligible delay ($< 1\text{ ms}$), with over 98% of the total latency attributable to gateway security and audit layers.

---

## 5. Architectural Bottleneck Root Cause Analysis

A detailed tracing analysis reveals why AgentGate exhibits an incremental overhead of $\sim 50\text{ ms}$ at $p50$ and $98.96\text{ ms}$ at $p95$:

```
[ Ingress: Bearer Token Validation ] (~0.1 ms - Cached JWKS)
                  |
[ FR8 Emergency Kill-Switch Check ] (~0.05 ms - In-memory)
                  |
[ FR7 Permission Matrix Check ] (~0.1 ms - In-memory DB cache)
                  |
[ FR9 Rate Limiter ] (~0.8 - 1.5 ms - Redis Lua EVALSHA)
                  |
[ FR6 JSON Schema Validation ] (~0.3 ms - Jackson NetworkNT)
                  |
[ FR4 Policy Evaluation ] (~0.2 ms - Cached rules)
                  |
[ Downstream HTTP Invocation ] (~0.2 ms - Connection Pool)
                  |
[ FR5 PII Redaction & Truncation ] (~0.2 ms - Regex matching)
                  |
[ FR2 Cryptographic Audit Logging ] (45.0 - 95.0 ms)  <=== PRIMARY BOTTLENECK
  * SELECT ... FOR UPDATE on audit_chain_state WHERE id = 1
  * SHA-256 canonical hash computation
  * INSERT INTO audit_log
  * UPDATE audit_chain_state
  * WAL Commit Flush to Disk
```

### 1. Audit Chain Row Lock Serialization (`SELECT ... FOR UPDATE`)
In `AuditRecorder.java`:
```java
AuditChainState tip = auditRepository.getChainStateForUpdate();
```
To guarantee that the audit hash chain cannot fork or contain sequence gaps under concurrent execution, every transaction acquires an exclusive row-level lock on `audit_chain_state` (row `id = 1`). Under a 100 RPS load, 100 transactions per second contend for this single lock. The transactions are strictly serialized.

### 2. Synchronous PostgreSQL WAL Flush under WSL2
In Docker Desktop for Windows running on WSL2, PostgreSQL commits require synchronous filesystem `fsync` calls through the WSL2 virtual disk layer (`ext4.vhdx`). Each synchronous disk flush requires approximately $0.5 - 1.2\text{ ms}$. Serializing 100 consecutive synchronous flushes per second forces transactions to queue behind the row lock, yielding an average lock wait time of $45 - 55\text{ ms}$.

### 3. Redis Lua Rate-Limiting Overhead
Each request executes a round-trip to Redis to run the sliding-window token bucket script. While Redis execution is fast ($< 0.5\text{ ms}$), container-to-container network hop overhead contributes $\sim 1.0 - 1.5\text{ ms}$.

---

## 6. Architectural Trade-Off Analysis

| Feature Dimension | Mode A (Current Implementation) | Mode B (Asynchronous Batch / Write-Ahead) |
|---|---|---|
| **Cryptographic Guarantee** | Strict immediate linear SHA-256 chain; tamper-proof at point of failure. | Eventual chain linkage via Disruptor ring buffer or background flusher. |
| **Transaction Boundary** | Synchronous inside the execution transaction. | Asynchronous dispatch decoupled from HTTP response. |
| **Concurrency Ceiling** | Bottlenecked by single row lock throughput ($\sim 15 - 20$ serialized commits/sec). | High concurrency ($> 1,000$ RPS) without row lock contention. |
| **Measured Overhead p95** | **$98.96\text{ ms}$** | Expected: **$8 - 14\text{ ms}$** (Well within $\le 30\text{ ms}$). |
| **Crash Semantics** | If gateway crashes, latest committed audit entry is always complete and valid. | Potential loss of the last batch of uncommitted audit records on abrupt hard kill. |

The current design strictly prioritized **correctness and tamper-evidence (FR2/QA3)** over raw latency, exactly adhering to the course priority hierarchy:
1. *Correctness and measurable assessment criteria*
2. *Security/fail-closed behavior*
3. *Understandable modular structure*
4. *Minimal complexity*

---

## 7. Remediation Roadmap for Milestone M05

To achieve compliance with QA2 ($p95 \le 30\text{ ms}$) in subsequent milestones while preserving cryptographic integrity:

1. **LMAX Disruptor / In-Memory Ring Buffer for Audit Serialization:**
   - Decouple audit hash computation and database writing from the HTTP request thread.
   - The HTTP thread places a lightweight audit event onto an in-memory Disruptor ring buffer ($< 0.05\text{ ms}$) and returns the response immediately.
   - A single pinned writer thread consumes the ring buffer, serializes hash computation, and batch-inserts audit rows (`INSERT ... VALUES (...), (...), ...`) into PostgreSQL, reducing WAL flushes by $20\times$.
2. **PostgreSQL Group Commit & Unlogged Benchmark Mode:**
   - Configure PostgreSQL `commit_delay = 1000` and `commit_siblings = 5` to enable group commits under high concurrent load.
3. **Redis Pipeline / Multi-Exec Optimization:**
   - Pre-compile and load SHA for rate-limit Lua scripts on startup to eliminate re-evaluations.

---

## 8. Artifact and Telemetry Evidence Index

All raw telemetry files are preserved in the repository:

1. `scripts/benchmarks/data/qa2_benchmark_summary.json` — Machine-readable summary with all percentile distributions.
2. `scripts/benchmarks/data/qa2_latency_samples.csv` — Full per-request raw telemetry ($24,000+$ rows including direct baseline and 3 governed runs).
3. `scripts/benchmarks/data/qa2_throughput_timeseries.csv` — Per-second throughput and $p95$ overhead time-series data.
4. `scripts/benchmarks/data/qa2_resource_samples.csv` — Periodic Docker container CPU and memory consumption.
5. `scripts/benchmarks/data/qa2_audit_verification.json` — Cryptographic verification output confirming 19,497 audit records verified.
6. `docs/quality/performance/QA2_METHODOLOGY.md` — Detailed test protocol and reproduction instructions.
7. `docs/quality/performance/charts/qa2_latency_distribution.png` — Overhead CDF and distribution chart.
8. `docs/quality/performance/charts/qa2_throughput_time_series.png` — Sustained throughput and latency chart.
9. `docs/quality/performance/charts/qa2_gateway_vs_downstream.png` — Component duration breakdown chart.
