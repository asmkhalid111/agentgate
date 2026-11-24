# AgentGate — QA2 Final Acceptance Report (Task 16F-E)

**Document Reference:** `docs/quality/performance/QA2_FINAL_ACCEPTANCE.md`  
**Milestone:** M04 Performance Optimization — Task 16F-E  
**Author:** AgentGate Architecture & Diagnostic Agent  
**Date:** 2026-11-25  
**Baseline Git Commit:** `0f39e2c64017b1dd09c8c9a7216a26395cc0f591`  
**Verdict:** **QA2: FAIL** *(Pooled Gateway-Added Overhead p95 = 342.05 ms > 30.0 ms Ceiling; Audit Lock Contention Confirmed)*

---

## 1. Executive Summary & Verdict

Task 16F-E executed the authoritative 18,000-call QA2 acceptance benchmark across three 60-second measurement windows at a target offered load of 100 RPS, preceded by a 7,500-call multi-rate warm-up and stabilization phase, using `SingleBufferHTTPConnection` (HTTP/1.1 persistent keep-alive, `TCP_NODELAY=1`, consolidated wire framing) and keeping all security, authorization, governance, and synchronous PostgreSQL SHA-256 audit logging enabled.

### Final Verdict: **FAIL**
1. **Success & Throughput:** All **18,000 / 18,000** scheduled acceptance calls completed as genuine governed tool executions (`100.0%` success rate, 0 failures, 0 timeouts). Achieved successful throughput was **99.85 RPS** pooled (`99.75`, `99.95`, `99.85` RPS per window, with `59.991s` dispatch duration and `60.03–60.15s` drain duration).
2. **Pre-Pipeline Ingress & MCP Transport:** `SingleBufferHTTPConnection` permanently eliminated the ~44 ms MCP parse stall. Across 18,000 calls, `ingress_mcp_parse` ($T_2 \to T_3$) measured **$p95 = 1.72\text{ ms}$**, and total pre-pipeline `security_ingress` measured **$p95 = 8.55\text{ ms}$**.
3. **Median vs. Tail Latency:** In stabilized Windows 2 and 3, **median (`p50`) gateway overhead was $10.05\text{ ms}$ and $9.63\text{ ms}$**, proving the gateway pipeline is fast under normal uncontended execution.
4. **Why QA2 Failed:** **Pooled gateway-added overhead `p95` measured $342.05\text{ ms}$** (Window 1: $589.18\text{ ms}$, Window 2: $110.25\text{ ms}$, Window 3: $110.34\text{ ms}$), exceeding the **$\le 30.0\text{ ms}$** QA2 ceiling.
5. **Root Cause of Failure:** Synchronous PostgreSQL row-lock contention on `audit_chain_state` (`SELECT ... FOR UPDATE`) combined with synchronous WAL disk commits (`synchronous_commit = on`). Pooled `audit_total` $p95$ was **$323.42\text{ ms}$**, of which `audit_lock_wait` $p95$ was **$314.22\text{ ms}$** (97.2% of audit latency). Warm-up did **not** eliminate this contention.

---

## 2. Timing Boundary Verification (`BenchmarkTimingFilter`)

Prior to execution, `BenchmarkTimingFilter.java` and `BenchmarkTimingContext.java` were inspected:
- `BenchmarkTimingFilter` is registered at `@Order(Ordered.HIGHEST_PRECEDENCE)` extending `OncePerRequestFilter`.
- `startNs = System.nanoTime()` is captured before `filterChain.doFilter(request, response)`, and `totalNs = System.nanoTime() - startNs` is computed in the `finally` block after `doFilter` returns.
- For `POST /mcp` `tools/call` requests, `WebMvcStreamableServerTransportProvider` executes the governed tool callback (`DynamicGovernedToolCallback` -> `ToolExecutionPipeline` -> `RestToolAdapter` -> `DefaultAuditService` -> PII redaction -> JSON-RPC response serialization) synchronously on the servlet thread before `doFilter` returns. This was verified by the presence of `X-Gateway-Duration-Ns`, `X-Downstream-Duration-Ns > 0`, and all `X-Profile-*` headers on the committed HTTP response.
- **Explicit Exclusion Documented:** Any final kernel socket buffer flush occurring in Tomcat after `BenchmarkTimingFilter` sets the response headers and exits `doFilter` is excluded from `X-Gateway-Duration-Ns`, but is captured in `client_latency_ms` (measured end-to-end from client socket write to full HTTP body read, typically ~5–6 ms above `gateway_duration_ms` including loopback TCP).
- **Authoritative Metric:**
  $$\text{Overhead}_i = D_{\text{gateway}, i} - D_{\text{downstream}, i}$$
  All percentiles (`p50`, `p90`, `p95`, `p99`) were computed directly from the sorted arrays of individual request-level overhead values.

---

## 3. Calibration & Warm-up Results (Excluded from Acceptance)

### 3.1 Phase 0: Calibration Gate (1 Probe)
- **Status:** `200 OK`, `valid = true`, `tcp_nodelay = 1`
- **Client Latency:** $327.52\text{ ms}$ | **Gateway Duration:** $284.50\text{ ms}$ | **Downstream:** $100.29\text{ ms}$ | **Overhead:** $184.22\text{ ms}$ (cold JVM first-request JIT)

### 3.2 Phase 1: Multi-Rate Warm-up & Stabilization (7,500 Calls)

| Phase | Valid / Planned | Achieved RPS | Dispatch Dur | Drain Dur | Lag p95 | Overhead p50 | Overhead p95 | Audit p95 | Audit Lock p95 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `warmup_10rps` | 150 / 150 | 10.05 | 14.901 s | 14.922 s | 3.07 ms | 19.40 ms | 119.33 ms | 25.49 ms | 8.16 ms |
| `warmup_30rps` | 450 / 450 | 30.02 | 14.967 s | 14.990 s | 1.27 ms | 13.94 ms | 68.50 ms | 21.61 ms | 6.58 ms |
| `warmup_60rps` | 900 / 900 | 59.74 | 14.984 s | 15.065 s | 20.59 ms | 19.30 ms | 289.49 ms | 234.55 ms | 217.43 ms |
| `warmup_stabilization` | 6,000 / 6,000 | 88.06 | 59.991 s | 68.135 s | 8,135.68 ms | 150.41 ms | 861.05 ms | 841.96 ms | 829.08 ms |

---

## 4. Measured Acceptance Results (18,000 Calls @ 100 RPS)

### 4.1 Per-Window and Pooled Throughput & Accounting

| Window | Planned | Scheduled | Dispatched | Completed | Successful | Failed / Timeout / Unresolved | Concurrency | Dispatch Dur | Drain Dur | Achieved RPS | Lag p95 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `window_1` | 6,000 | 6,000 | 6,000 | 6,000 | **6,000** | 0 / 0 / 0 | 25 | 59.991 s | 60.152 s | **99.75** | 591.47 ms |
| `window_2` | 6,000 | 6,000 | 6,000 | 6,000 | **6,000** | 0 / 0 / 0 | 25 | 59.991 s | 60.030 s | **99.95** | 1.22 ms |
| `window_3` | 6,000 | 6,000 | 6,000 | 6,000 | **6,000** | 0 / 0 / 0 | 25 | 59.991 s | 60.088 s | **99.85** | 1.12 ms |
| **POOLED** | **18,000** | **18,000** | **18,000** | **18,000** | **18,000** | **0 / 0 / 0** | **25** | **179.973 s** | **180.270 s** | **99.85** | **483.34 ms** |

### 4.2 Per-Window and Pooled Latency Percentiles (ms)

| Window | Overhead Mean | Overhead p50 | Overhead p90 | **Overhead p95** | Overhead p99 | Overhead Max | Ingress p95 | MCP Parse (T2→T3) p95 | Audit Total p95 | Audit Lock Wait p95 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `window_1` | 146.10 | 57.13 | 409.84 | **589.18** | 1,063.43 | 2,377.79 | 11.68 | 3.00 | 574.41 | 561.63 |
| `window_2` | 28.60 | 10.05 | 53.07 | **110.25** | 339.26 | 1,100.10 | 6.04 | 1.21 | 88.70 | 77.85 |
| `window_3` | 28.34 | 9.63 | 56.09 | **110.34** | 334.51 | 1,032.30 | 5.23 | 0.94 | 98.49 | 89.27 |
| **POOLED** | **67.68** | **13.39** | **178.95** | **342.05** | **771.64** | **2,377.79** | **8.55** | **1.72** | **323.42** | **314.22** |

---

## 5. Audit Chain Reconciliation & Resource Telemetry

### 5.1 Cryptographic Audit Chain Verification

| Check | Status | Records Checked | Last Sequence | Corrupted Sequence |
| :--- | :--- | :--- | :--- | :--- |
| **Pre-Benchmark** | `valid: true` | 30,310 | 30,310 | `null` |
| **Post-Benchmark** | `valid: true` | **55,811** | **55,811** | **`null`** |

**Exact Sequence Reconciliation:**
$$30,310\text{ (initial)} + 1\text{ (calibration)} + 7,500\text{ (warm-up)} + 18,000\text{ (acceptance)} = \mathbf{55,811}$$
Zero missing records, zero corrupted hashes.

### 5.2 PostgreSQL Row-Lock & Container Resource Observations
- **PostgreSQL `audit_chain_state` Lock Polling (0.5s intervals):**
  - `warmup_10rps` & `warmup_30rps`: `0` waiting locks.
  - `warmup_stabilization`: `max_waiting = 21`, `mean_waiting = 16.31` (66/70 samples non-zero).
  - `window_1`: `max_waiting = 21`, `mean_waiting = 9.64` (47/70 samples non-zero).
  - `window_2`: `max_waiting = 21`, `mean_waiting = 0.80` (10/82 samples non-zero).
  - `window_3`: `max_waiting = 23`, `mean_waiting = 1.25` (13/84 samples non-zero).
- **Container Resource Utilization (`docker stats`):**
  - `agentgate-benchmark-gateway`: CPU mean `155.7%`, max `304.5%`; Memory max `479.2 MB` / `512 MB` limit (93.6% utilization).
  - `agentgate-benchmark-postgres`: CPU mean `59.3%`, max `166.1%`; Memory max `123.1 MB` / `256 MB` limit.
  - `agentgate-benchmark-demo-tools`: CPU mean `24.9%`, max `126.6%`; Memory max `160.4 MB` / `192 MB` limit.
  - `agentgate-benchmark-redis`: CPU mean `5.9%`, max `15.7%`; Memory max `8.5 MB` / `64 MB` limit.
- **Note on HikariCP Actuator Metrics:** `/actuator/metrics/hikaricp.connections.*` was not accessible unauthenticated (`ResourceMonitor` recorded 0 samples); PostgreSQL lock polling and container stats provide direct contention evidence.

---

## 6. Evidence Paths & Reproduction

- **Acceptance Charts:**
  - [qa2_final_acceptance_windows.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_final_acceptance_windows.png)
  - [qa2_final_latency_cdf.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_final_latency_cdf.png)
- **Datasets:**
  - [qa2_final_acceptance_samples.csv](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_acceptance_samples.csv) (18,000 rows)
  - [qa2_final_acceptance_summary.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_acceptance_summary.json)
  - [qa2_final_warmup_samples.csv](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_warmup_samples.csv) (7,500 rows)
  - [qa2_final_warmup_summary.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_warmup_summary.json)
  - [qa2_final_lock_stats.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_lock_stats.json)
  - [qa2_final_resource_stats.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_final_resource_stats.json)
- **Scripts:**
  - [run_qa2_final_acceptance.py](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/run_qa2_final_acceptance.py)
  - [generate_final_acceptance_charts.py](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/generate_final_acceptance_charts.py)
- **Reproduction Command:** `python -u scripts/benchmarks/run_qa2_final_acceptance.py`
