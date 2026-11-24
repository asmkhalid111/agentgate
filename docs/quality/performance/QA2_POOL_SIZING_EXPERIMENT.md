# AgentGate — M04 Task 16F-B: HikariCP Connection Pool Sizing Experiment

**Milestone:** M04  
**Date:** November 24, 2026  
**Status:** COMPLETE — EMPIRICAL VALIDATION  
**Harness Baseline:** Monotonic Nanosecond In-Process Profiling (Isolated Benchmark Environment)  
**Authoritative QA2 Requirement:**
> *"The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."*

---

## 1. Executive Summary

Task 16F-B evaluated the architectural hypothesis formulated in Task 16F-A: **that the catastrophic row-lock waiting ($281.37\text{ ms}$ at $p95$) and permission-check latency ($98.76\text{ ms}$ at $p95$) observed in Task 16E were primarily induced by HikariCP connection pool starvation**.

Under a 10-connection pool with 20 concurrent HTTP client worker threads dispatching 100 RPS, transactions executing `recordDirectToolExecution` queued sequentially on `audit_chain_state WHERE id = 1`. Each waiting transaction held a physical database connection, tying up 8–9 connections and starving non-audit metadata queries (`findByName`, `isAgentPermitted`, `checkBlocked`).

By expanding the HikariCP connection pool to **30 connections** (`maximum-pool-size: 30`, `minimum-idle: 15`) in `application-benchmark.yml`, the pool size exceeded the 20 concurrent client worker threads.

### Decisive Empirical Findings
1. **Audit Row-Lock Wait Reduced by 99.1%:**
   - In Task 16E (Pool 10), `audit_lock_wait` reached **$281.37\text{ ms}$** at $p95$.
   - In Task 16F-B (Pool 30), `audit_lock_wait` dropped to **$2.50\text{ ms}$** at $p95$ ($0.47\text{ ms}$ median).
   - This proves that true PostgreSQL row locking takes $< 2.5\text{ ms}$; the previously observed hundreds of milliseconds were connection-borrow queueing inside HikariCP.
2. **Permission Check Latency Reduced by 98.0%:**
   - In Task 16E (Pool 10), `permission_check` reached **$98.76\text{ ms}$** at $p95$.
   - In Task 16F-B (Pool 30), `permission_check` dropped to **$2.00\text{ ms}$** at $p95$ ($0.94\text{ ms}$ median).
3. **Total Audit Subsystem Overhead Reduced by 97.8%:**
   - In Task 16E (Pool 10), `audit_total` reached **$354.58\text{ ms}$** at $p95$.
   - In Task 16F-B (Pool 30), `audit_total` dropped to **$7.77\text{ ms}$** at $p95$ ($2.87\text{ ms}$ median).
4. **Overall Gateway Overhead Reduced by 86.9%:**
   - In Task 16E (Pool 10), net gateway overhead reached **$447.71\text{ ms}$** at $p95$.
   - In Task 16F-B (Pool 30), net gateway overhead dropped to **$58.78\text{ ms}$** at $p95$ ($49.27\text{ ms}$ median).
5. **Cryptographic Integrity 100% Preserved:**
   - 13,502 total audit records verified via `AuditVerificationService.verifyIntegrity()`.
   - Zero sequence gaps, zero hash corruptions, 100% mathematical validity.

---

## 2. Test Configuration & Protocol

### 2.1 Benchmark Environment Configuration

| Parameter | Task 16E Baseline | Task 16F-B Experiment |
|---|---|---|
| **HikariCP Maximum Pool Size** | `10` (Spring Boot default) | **`30`** (`maximum-pool-size: 30`) |
| **HikariCP Minimum Idle** | `10` (default) | **`15`** (`minimum-idle: 15`) |
| **HikariCP Connection Timeout** | `30000 ms` | `20000 ms` |
| **HikariCP Pool Name** | `HikariPool-1` | `AgentGateBenchmarkPool` |
| **Client Concurrency** | 20 worker threads | 20 worker threads |
| **PostgreSQL Container** | `postgres:16-alpine` (256 MB limit) | `postgres:16-alpine` (256 MB limit) |
| **Redis Container** | `redis:7-alpine` (64 MB limit) | `redis:7-alpine` (64 MB limit) |
| **Gateway Container** | Java 21 LTS (512 MB limit) | Java 21 LTS (512 MB limit) |
| **Active Profiles** | `dev,benchmark` | `dev,benchmark` |

### 2.2 Workload Protocol (Identical Across Both Runs)

The profiling suite executed 13,500 total governed MCP requests across five distinct arrival rates plus a 60-second stabilization phase:

| Phase Name | Target Rate | Duration | Total Requests | Client Concurrency |
|---|---|---|---|---|
| `profile_10rps` | 10.0 RPS | 15.0 s | 150 | 10 workers |
| `profile_30rps` | 30.0 RPS | 15.0 s | 450 | 15 workers |
| `profile_60rps` | 60.0 RPS | 15.0 s | 900 | 20 workers |
| `profile_stabilization` | 100.0 RPS | 60.0 s | 6,000 | 20 workers |
| `profile_100rps_win1` | 100.0 RPS | 30.0 s | 3,000 | 20 workers |
| `profile_100rps_win2` | 100.0 RPS | 30.0 s | 3,000 | 20 workers |

---

## 3. Measured Results across Arrival Rates (Pool Size = 30)

All values represent monotonic, non-overlapping nanosecond execution spans measured in milliseconds:

### 3.1 Component Median ($p50$) Latency across Arrival Rates

| Component / Sub-Operation | 10 RPS | 30 RPS | 60 RPS | 100 RPS (Win 1) | 100 RPS (Win 2) | Scaling Trend |
|---|---|---|---|---|---|---|
| **Security Ingress** | 41.76 ms | 43.14 ms | 43.51 ms | 43.68 ms | 43.72 ms | Constant baseline (~43 ms) |
| **Permission Check** | 1.94 ms | 1.88 ms | 1.62 ms | 0.94 ms | 0.94 ms | Accelerates to $< 1\text{ ms}$ |
| **Rate Limiter** | 1.89 ms | 1.62 ms | 1.48 ms | 0.58 ms | 0.55 ms | Sub-millisecond |
| **Schema Validation** | 0.30 ms | 0.16 ms | 0.11 ms | 0.04 ms | 0.04 ms | JIT optimized to $40\ \mu\text{s}$ |
| **Policy Evaluation** | 0.011 ms | 0.009 ms | 0.007 ms | 0.004 ms | 0.004 ms | In-memory cached ($4\ \mu\text{s}$) |
| **Circuit Breaker** | 0.007 ms | 0.005 ms | 0.005 ms | 0.002 ms | 0.002 ms | Atomic counter ($2\ \mu\text{s}$) |
| **Downstream Adapter** | 5.26 ms | 3.65 ms | 3.24 ms | 1.63 ms | 1.58 ms | Healthy REST mock |
| **Audit: Lock Wait** | 0.88 ms | 0.74 ms | 0.65 ms | 0.44 ms | 0.47 ms | **$< 0.5\text{ ms}$ steady state** |
| **Audit: Hash Calc** | 0.15 ms | 0.08 ms | 0.06 ms | 0.03 ms | 0.04 ms | Negligible CPU hashing |
| **Audit: DB Write** | 1.92 ms | 1.74 ms | 1.52 ms | 0.72 ms | 0.71 ms | Single insert query |
| **Audit: Commit & Flush**| 2.14 ms | 1.95 ms | 1.88 ms | 1.58 ms | 1.59 ms | Grouped WAL sync |
| **Audit: Total** | 5.34 ms | 4.72 ms | 4.29 ms | 2.89 ms | 2.87 ms | **$< 3\text{ ms}$ steady state** |
| **PII & Truncation** | 0.28 ms | 0.21 ms | 0.16 ms | 0.08 ms | 0.09 ms | Sub-0.1 ms |
| **MCP Serialization** | 0.03 ms | 0.02 ms | 0.02 ms | 0.01 ms | 0.01 ms | Negligible Jackson stream |
| **Total Net Overhead ($p50$)**| **53.53 ms** | **51.28 ms** | **50.43 ms** | **49.25 ms** | **49.27 ms** | **Flat, stable scaling** |

---

### 3.2 Component Tail ($p95$) Latency across Arrival Rates

| Component / Sub-Operation | 10 RPS | 30 RPS | 60 RPS | 100 RPS (Win 1) | 100 RPS (Win 2) | Impact on 30ms SLA |
|---|---|---|---|---|---|---|
| **Security Ingress** | 47.69 ms | 47.92 ms | 48.01 ms | 48.01 ms | **48.02 ms** | **Primary Contributor (81.7%)** |
| **Permission Check** | 5.62 ms | 4.39 ms | 3.12 ms | 2.01 ms | **2.00 ms** | **Within SLA budget** |
| **Rate Limiter** | 3.70 ms | 2.88 ms | 2.45 ms | 1.25 ms | **1.20 ms** | Minimal RTT |
| **Schema Validation** | 0.65 ms | 0.38 ms | 0.22 ms | 0.09 ms | **0.09 ms** | Negligible |
| **Policy Evaluation** | 0.03 ms | 0.01 ms | 0.01 ms | 0.01 ms | **0.01 ms** | Negligible |
| **Circuit Breaker** | 0.02 ms | 0.01 ms | 0.01 ms | 0.01 ms | **0.01 ms** | Negligible |
| **Downstream Adapter** | 10.41 ms | 8.12 ms | 6.55 ms | 3.22 ms | **3.18 ms** | Fast REST mock |
| **Audit: Lock Wait** | 1.27 ms | 1.25 ms | 1.09 ms | 1.27 ms | **2.50 ms** | **Eliminated ($2.50\text{ ms}$)** |
| **Audit: Hash Calc** | 0.32 ms | 0.15 ms | 0.11 ms | 0.07 ms | **0.07 ms** | Negligible |
| **Audit: DB Write** | 4.12 ms | 3.25 ms | 2.88 ms | 1.70 ms | **1.68 ms** | Stable |
| **Audit: Commit & Flush**| 4.88 ms | 3.92 ms | 3.55 ms | 3.12 ms | **3.09 ms** | Stable WAL sync |
| **Audit: Total** | 9.85 ms | 8.12 ms | 7.15 ms | 7.65 ms | **7.77 ms** | **Eliminated ($7.77\text{ ms}$)** |
| **PII & Truncation** | 0.62 ms | 0.42 ms | 0.31 ms | 0.15 ms | **0.15 ms** | Negligible |
| **MCP Serialization** | 0.07 ms | 0.04 ms | 0.03 ms | 0.01 ms | **0.01 ms** | Negligible |
| **Total Net Overhead ($p95$)**| **66.36 ms** | **59.32 ms** | **58.66 ms** | **56.60 ms** | **58.78 ms** | **Reduced from $447.71\text{ ms}$** |

---

## 4. Controlled Comparison: Pool 10 vs. Pool 30 at 100 RPS

Comparing the fully stabilized 100 RPS steady-state window (Window 2) between Task 16E (Pool Size = 10) and Task 16F-B (Pool Size = 30):

```
Component Tail Latency (p95) Comparison:
-----------------------------------------------------------------------------------------
Audit Lock Wait:
  Pool 10 [==================================================] 281.4 ms
  Pool 30 [=] 2.5 ms  (-99.1%)

Permission Check:
  Pool 10 [==================] 98.8 ms
  Pool 30 [=] 2.0 ms  (-98.0%)

Audit Commit:
  Pool 10 [=============] 73.2 ms
  Pool 30 [=] 3.1 ms  (-95.8%)

Audit Total Subsystem:
  Pool 10 [==============================================================] 354.6 ms
  Pool 30 [==] 7.8 ms  (-97.8%)

Total Gateway Overhead (p95):
  Pool 10 [==============================================================================] 447.7 ms
  Pool 30 [==========] 58.8 ms  (-86.9%)
-----------------------------------------------------------------------------------------
QA2 SLA Ceiling: 30.0 ms
```

| Metric / Sub-Operation | Task 16E (Pool Size = 10) | Task 16F-B (Pool Size = 30) | Absolute Reduction | % Reduction |
|---|---|---|---|---|
| **Delivered Concurrency Throughput** | 92.20 RPS | **99.84 RPS** | +7.64 RPS | +8.3% |
| **Audit: Lock Wait ($p95$)** | 281.37 ms | **2.50 ms** | -278.87 ms | **-99.1%** |
| **Permission Check ($p95$)** | 98.76 ms | **2.00 ms** | -96.76 ms | **-98.0%** |
| **Audit: Commit & Flush ($p95$)** | 73.21 ms | **3.09 ms** | -70.12 ms | **-95.8%** |
| **Audit: DB Write ($p95$)** | 11.85 ms | **1.68 ms** | -10.17 ms | **-85.8%** |
| **Audit Subsystem Total ($p95$)**| 354.58 ms | **7.77 ms** | -346.81 ms | **-97.8%** |
| **Downstream Adapter ($p95$)** | 25.52 ms | **3.18 ms** | -22.34 ms | **-87.5%** |
| **Rate Limiter ($p95$)** | 11.62 ms | **1.20 ms** | -10.42 ms | **-89.7%** |
| **Total Gateway Overhead ($p50$)**| 154.87 ms | **49.27 ms** | -105.60 ms | **-68.2%** |
| **Total Gateway Overhead ($p95$)**| 447.71 ms | **58.78 ms** | -388.93 ms | **-86.9%** |

Visual evidence chart: [qa2_pool_comparison.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_pool_comparison.png)

---

## 5. PostgreSQL Contention and Lock Evidence

During the 13,500-request execution, background monitoring sampled PostgreSQL diagnostic tables (`pg_stat_activity` and `pg_locks`):

| Diagnostic Metric | Task 16E (Pool Size = 10) | Task 16F-B (Pool Size = 30) |
|---|---|---|
| **Max Blocked on `audit_chain_state`** | 8 concurrent transactions | **0 to 1 concurrent transaction** |
| **Max Concurrent Waiting Locks** | 9 locks | **0 to 1 lock** |
| **Connection Pool Saturation** | 90–100% (Starvation) | **Healthy (Peak Active: 12–16)** |
| **Transaction Duration** | 20–45 ms | **2–4 ms** |

---

## 6. Methodological Limitations & Incomplete Comparisons

1. **Configurations Measured:**
   - Evaluated: **Pool Size 10** (baseline) and **Pool Size 30** (experiment).
   - **Untested Configurations:** Intermediate pool sizes (e.g., 16 and 24) were not measured in this run. Testing pool size 30 directly evaluated the hypothesis that exceeding the 20 concurrent client worker threads resolves connection starvation. If fine-grained tuning between 16 and 24 is required, an incremental sweep can be scheduled.
2. **Sequential Execution Context:**
   - Both runs executed the identical 6-phase profiling workload (13,500 requests; 20 concurrency; identical nanosecond span instrumentation).
   - However, they ran in sequential Docker sessions. Host background load variations and WSL2 I/O conditions are acknowledged as minor confounding factors, though the magnitude of improvement (from 447 ms to 58 ms) overwhelmingly reflects the connection pool expansion.

---

## 7. Conclusions & Next Steps

1. **Asynchronous Audit Buffering is Unnecessary:**
   - Expanding the connection pool from 10 to 30 completely resolved the audit lock queueing ($281\text{ ms} \to 2.5\text{ ms}$) and commit flush contention ($73\text{ ms} \to 3.1\text{ ms}$).
   - The proposed complex asynchronous audit ring buffer (Option D) is **rejected**, avoiding durability risks and crash-loss vulnerabilities.
2. **Current Remaining Gap:**
   - Net gateway overhead $p95$ currently stands at **$58.78\text{ ms}$**, which is **$28.78\text{ ms}$ above the $30.0\text{ ms}$ SLA ceiling**.
   - Inside that $58.78\text{ ms}$, **Security Ingress accounts for $48.02\text{ ms}$ ($81.7\%$)**.
   - All other governed execution components (permissions, rate limit, schema, policy, circuit breaker, audit logging, PII, serialization) sum to **$< 11\text{ ms}$**!
3. **Recommended Next Phase:**
   - Optimize the Security Ingress filter chain (stateless JWT decoding and JWKS public key verification caching) to close the remaining $28.78\text{ ms}$ gap before rerunning the formal QA2 acceptance benchmark.
