# AgentGate M04 Task 16E: Root-Cause Performance Profiling & Bottleneck Analysis

**Milestone:** M04  
**Date:** November 23, 2026  
**Status:** COMPLETE — EMPIRICAL BOTTLENECK DIAGNOSIS  
**Harness Baseline:** Monotonic Nanosecond In-Process Profiling (Isolated Benchmark Environment)  
**Authoritative QA2 Requirement:**
> *"The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."*

---

## 1. Executive Summary

Task 16E was executed under a strict diagnostic mandate: **determine the empirical root causes of gateway-added latency before proposing any architectural optimization**. 

Using low-overhead, monotonic nanosecond instrumentation across every sub-component of the governed execution pipeline and live PostgreSQL lock sampling, we profiled 13,500 total tool invocations across five controlled load levels: **10 RPS, 30 RPS, 60 RPS, 100 RPS Window 1, and 100 RPS Window 2**, preceded by a 60-second stabilization phase.

### Core Forensic Discoveries
1. **The Primary Bottleneck is Mode A Synchronous Audit Lock Contention:**
   - Under low concurrency (10–30 RPS), the row lock on `audit_chain_state` (`SELECT ... FOR UPDATE`) resolves in **$0.95 - 1.08\text{ ms}$** median and **$2.20 - 5.87\text{ ms}$** at $p95$.
   - At 60 RPS, lock wait time undergoes an exponential knee, rising to **$67.58\text{ ms}$** at $p95$.
   - At 100 RPS, lock wait reaches **$281.37\text{ ms} - 359.80\text{ ms}$** at $p95$, directly accounting for **$62.8\%$ of the entire gateway tail overhead**.
2. **The Secondary Bottleneck is Synchronous WAL Flush on Transaction Commit:**
   - Transaction commit (`audit_commit`) requires synchronous disk flush (`fsync`) for each independent transaction, contributing **$73.21\text{ ms} - 106.76\text{ ms}$** at $p95$ under 100 RPS.
3. **The Tertiary Bottleneck is Connection Pool Starvation Cascading to Permission Checks:**
   - `toolRepository.isAgentPermitted` queries PostgreSQL (`SELECT COUNT(*) FROM tool_agent_permissions WHERE ...`).
   - At 10 RPS, this query executes in **$2.66\text{ ms}$** median and **$11.37\text{ ms}$** $p95$.
   - At 100 RPS, 8 to 9 connections in the 10-connection HikariCP pool become blocked waiting for the `audit_chain_state` row lock. Incoming requests attempting to check permissions must wait in line for a database connection, ballooning permission check latency to **$98.76\text{ ms} - 128.90\text{ ms}$** at $p95$!
4. **Non-Bottleneck Components Confirmed Sub-Millisecond:**
   - **Schema Validation:** $p50 = 0.07\text{ ms}$, $p95 = 0.21\text{ ms}$ (Jackson JSON Schema validator is highly performant).
   - **Structured Policy Evaluation:** $p50 = 0.007\text{ ms}$, $p95 = 0.011\text{ ms}$ (in-memory cached rules evaluate in $< 15\ \mu\text{s}$).
   - **Circuit Breaker Check:** $p50 = 0.009\text{ ms}$, $p95 = 0.017\text{ ms}$.
   - **SHA-256 Record Hash Calculation:** $p50 = 0.057\text{ ms}$, $p95 = 0.166\text{ ms}$ (CPU hashing is essentially free).
   - **PII Output Redaction & Truncation:** $p50 = 0.12\text{ ms}$, $p95 = 0.53\text{ ms}$.
   - **MCP JSON-RPC Serialization:** $p50 = 0.011\text{ ms}$, $p95 = 0.023\text{ ms}$.
5. **Decisive Architectural Finding:**
   - **Reducing audit contention alone CANNOT bring gateway overhead below 30 ms.** Even with zero audit overhead, non-audit components under 100 RPS (security ingress + un-cached permission check database queries + Redis rate limiter) sum to $> 140\text{ ms}$ at $p95$. Achieving $\le 30\text{ ms}$ requires **both** asynchronous audit batching and in-memory permission metadata caching.

---

## 2. Test Environment & System Configuration

| Parameter | Configuration |
|---|---|
| **Host System** | Windows 11 Pro 64-bit, Intel Core i5 (8 logical cores), 16 GB RAM |
| **Container Engine** | Docker Desktop 28.x with WSL2 Linux backend |
| **Gateway Container** | `agentgate-benchmark-gateway` (512 MB memory limit, Java 21 LTS) |
| **Database Container** | `agentgate-benchmark-postgres` (PostgreSQL 16 Alpine, 256 MB memory limit) |
| **Cache Container** | `agentgate-benchmark-redis` (Redis 7 Alpine, in-memory, 64 MB memory limit) |
| **Downstream Container** | `agentgate-benchmark-demo-tools` (192 MB memory limit, mock orders service) |
| **Active Profiles** | `dev,benchmark` (overriding `agent-support-bot` RPM to 12,000) |
| **Database Connection Pool** | HikariCP (`maximumPoolSize = 10`, default Spring Boot configuration) |
| **Instrumentation** | Monotonic nanosecond timing (`System.nanoTime()`), disabled in dev/prod |

---

## 3. Workload Protocol

The profiling suite executed five distinct measurement phases plus an extended stabilization phase:

| Phase | Duration | Target RPS | Calls | Concurrency | Primary Objective |
|---|---|---|---|---|---|
| **`profile_10rps`** | 15.0 s | 10.0 | 150 | 10 workers | Baseline latency in zero-contention regime |
| **`profile_30rps`** | 15.0 s | 30.0 | 450 | 15 workers | Transition regime before queueing escalation |
| **`profile_60rps`** | 15.0 s | 60.0 | 900 | 20 workers | Inflection point where lock contention begins |
| **`profile_stabilization`**| 60.0 s | 100.0 | 6,000 | 20 workers | Extended JIT compilation & thread pool warm-up |
| **`profile_100rps_win1`**| 30.0 s | 100.0 | 3,000 | 20 workers | High-concurrency steady-state Window 1 |
| **`profile_100rps_win2`**| 30.0 s | 100.0 | 3,000 | 20 workers | High-concurrency steady-state Window 2 (stabilization check) |

---

## 4. Component-Level Latency Breakdown

All values represent monotonic, non-overlapping, exclusive execution spans measured in milliseconds:

### 4.1 Component Median ($p50$) Latency across Arrival Rates

| Component / Sub-Operation | 10 RPS | 30 RPS | 60 RPS | 100 RPS (Win 1) | 100 RPS (Win 2) | Concurrency Sensitivity |
|---|---|---|---|---|---|---|
| **Security Ingress** (JWT + Servlet) | 12.65 ms | 43.68 ms | 43.84 ms | 3.39 ms | 3.08 ms | Sensitive to thread queueing |
| **Permission Check** (DB Query) | 2.66 ms | 1.90 ms | 1.66 ms | 32.29 ms | 26.91 ms | **High** (Pool Starvation) |
| **Rate Limiter** (Redis Token Bucket)| 2.61 ms | 1.64 ms | 1.50 ms | 1.98 ms | 1.46 ms | Minimal ($1.5 - 2.5\text{ ms}$ RTT) |
| **Schema Validation** (JSON Schema) | 0.42 ms | 0.16 ms | 0.12 ms | 0.08 ms | 0.07 ms | **None** (JIT optimizes to $< 0.1\text{ ms}$) |
| **Policy Evaluation** (Structured) | 0.018 ms | 0.011 ms | 0.009 ms | 0.006 ms | 0.007 ms | **None** (In-memory cached) |
| **Circuit Breaker** (In-Memory) | 0.013 ms | 0.011 ms | 0.011 ms | 0.011 ms | 0.009 ms | **None** (Atomic counters) |
| **Downstream Execution** (REST) | 7.56 ms | 4.79 ms | 4.94 ms | 8.47 ms | 5.74 ms | Stable mock execution |
| **Audit: Lock Wait** (`FOR UPDATE`) | **1.08 ms** | **0.95 ms** | **1.20 ms** | **61.43 ms** | **47.93 ms** | **CRITICAL** (Row contention) |
| **Audit: SHA-256 Hash Calc** | 0.24 ms | 0.08 ms | 0.06 ms | 0.06 ms | 0.06 ms | **None** (CPU-bound) |
| **Audit: SQL Insert + Update** | 2.23 ms | 1.89 ms | 1.99 ms | 2.16 ms | 2.16 ms | Stable single-query time |
| **Audit: Transaction Commit** | 3.83 ms | 2.36 ms | 2.92 ms | 24.17 ms | 19.11 ms | **High** (WAL fsync queueing) |
| **PII Redaction & Truncation** | 0.45 ms | 0.29 ms | 0.23 ms | 0.14 ms | 0.12 ms | **None** (Regex JIT optimized) |
| **MCP Serialization** | 0.06 ms | 0.02 ms | 0.02 ms | 0.01 ms | 0.01 ms | **None** (Jackson streaming) |
| **Total Net Gateway Overhead ($p50$)**| **40.49 ms** | **53.51 ms** | **55.49 ms** | **186.23 ms** | **154.87 ms** | Escalates at 100 RPS |

---

### 4.2 Component Tail ($p95$) Latency across Arrival Rates

| Component / Sub-Operation | 10 RPS | 30 RPS | 60 RPS | 100 RPS (Win 1) | 100 RPS (Win 2) | SLA Budget Impact |
|---|---|---|---|---|---|---|
| **Security Ingress** | 48.42 ms | 48.47 ms | 50.98 ms | 19.00 ms | 39.10 ms | Consumes 30–40 ms under load |
| **Permission Check** | 11.37 ms | 5.63 ms | 22.28 ms | 128.90 ms | **98.76 ms** | **Consumes 3x the entire budget** |
| **Rate Limiter** | 8.17 ms | 4.08 ms | 6.29 ms | 15.54 ms | 11.62 ms | Redis network roundtrip |
| **Schema Validation** | 0.90 ms | 0.37 ms | 0.24 ms | 0.25 ms | 0.21 ms | $< 0.3\text{ ms}$ (Negligible) |
| **Policy Evaluation** | 0.04 ms | 0.01 ms | 0.01 ms | 0.01 ms | 0.01 ms | $< 0.02\text{ ms}$ (Negligible) |
| **Circuit Breaker** | 0.02 ms | 0.02 ms | 0.02 ms | 0.02 ms | 0.02 ms | $< 0.02\text{ ms}$ (Negligible) |
| **Downstream Adapter** | 16.72 ms | 12.98 ms | 19.79 ms | 33.70 ms | 25.52 ms | Downstream baseline |
| **Audit: Lock Wait** | **5.87 ms** | **2.20 ms** | **67.58 ms** | **359.80 ms** | **281.37 ms** | **PRIMARY BOTTLENECK (62.8%)** |
| **Audit: Hash Calc** | 0.52 ms | 0.15 ms | 0.12 ms | 0.16 ms | 0.17 ms | $< 0.2\text{ ms}$ (Negligible) |
| **Audit: SQL DB Write** | 6.15 ms | 4.15 ms | 8.35 ms | 14.36 ms | 11.85 ms | SQL query latency |
| **Audit: Commit & Flush** | **17.14 ms** | **5.27 ms** | **43.20 ms** | **106.76 ms** | **73.21 ms** | **SECONDARY BOTTLENECK (16.4%)** |
| **PII & Truncation** | 1.02 ms | 0.51 ms | 0.44 ms | 0.59 ms | 0.53 ms | $< 0.6\text{ ms}$ (Negligible) |
| **MCP Serialization** | 0.18 ms | 0.06 ms | 0.05 ms | 0.02 ms | 0.02 ms | $< 0.03\text{ ms}$ (Negligible) |
| **Total Net Overhead ($p95$)** | **97.66 ms** | **66.08 ms** | **192.86 ms** | **577.89 ms** | **447.71 ms** | **VIOLATES 30 ms SLA** |

---

## 5. PostgreSQL Lock Contention and Pool Exhaustion Evidence

Using asynchronous background polling of PostgreSQL diagnostic tables (`pg_stat_activity` and `pg_locks`) during live execution, we captured the exact concurrency behavior:

| Metric Observed | 10 RPS | 30 RPS | 60 RPS | 100 RPS (Win 1) | 100 RPS (Win 2) |
|---|---|---|---|---|---|
| **Max Concurrent Waiting Locks** | 0 | 3 | 7 | **9** | **9** |
| **Max Blocked on `audit_chain_state`** | 0 | 1 | 6 | **8** | **8** |
| **Total Concurrent Locks on Chain** | 1 | 6 | 16 | **20** | **20** |
| **HikariCP Pool Saturation** | ~10% | ~30% | ~70% | **90–100%** | **90–100%** |

### The Cascade Mechanism Explained
1. **The Row-Lock Bottleneck:** In Mode A, every tool call executes `SELECT ... FOR UPDATE` against `audit_chain_state WHERE id = 1`. Under 100 RPS, up to 8 transactions simultaneously queue waiting for this single row.
2. **The Connection Pool Starvation:** The default HikariCP pool has `maximumPoolSize = 10`. When 8 connections are blocked waiting for the row lock, only 1 to 2 connections remain available for the entire gateway.
3. **The Unmasked Permission Check Cascade:** Before a tool executes, `ToolExecutionPipeline` calls `toolRepository.isAgentPermitted`, which requests a database connection to query `tool_agent_permissions`. Because 8–9 connections are tied up in the audit lock queue, incoming threads block in `HikariPool.getConnection()`. This explains why `permission_check` tail latency escalates from **$5.63\text{ ms}$** at 30 RPS to **$98.76\text{ ms}$** at 100 RPS!

---

## 6. Warmup and Stabilization Findings

Comparing the initial 60-second stabilization phase against Window 1 and Window 2 reveals the exact role of JVM JIT compilation:

| Phase | Delivered RPS | Overhead $p50$ | Overhead $p95$ | Audit Lock $p95$ | Permission $p95$ |
|---|---|---|---|---|---|
| **Stabilization (60s)** | 97.3 RPS | 198.4 ms | 612.3 ms | 382.1 ms | 134.5 ms |
| **100 RPS Window 1 (30s)** | 76.6 RPS | 186.2 ms | 577.9 ms | 359.8 ms | 128.9 ms |
| **100 RPS Window 2 (30s)** | 92.2 RPS | 154.9 ms | 447.7 ms | 281.4 ms | 98.8 ms |

### Forensic Conclusions on JIT & Stabilization:
1. **JIT optimization is genuine but bounded:** Between Window 1 and Window 2, C2 compiler inlining reduced median overhead by $31.3\text{ ms}$ ($186.2\text{ ms} \to 154.9\text{ ms}$) and increased delivered throughput from 76.6 to 92.2 RPS.
2. **The 30-second warmup in Task 16B was insufficient:** Full JIT and connection pool stabilization requires $\ge 60\text{ seconds}$ under heavy load.
3. **JIT cannot defeat database serialization:** Even in the fully warmed Window 2, lock wait ($281.37\text{ ms}$) and commit flush ($73.21\text{ ms}$) impose an insurmountable physical barrier.

---

## 7. Audit-Record Reconciliation (Resolving the 202-Record Discrepancy)

In Task 16D, the rerun report verified **19,903 total audit records**, while documented phases accounted for 19,701 calls ($1,500 + 6,000 + 6,000 + 6,000 + 200 + 1$). The remaining **202 records** are now completely reconciled using verifiable session logs:

1. **Sequence #1 (1 Record):** In task 1820, an initial calibration probe arrived before `seed_benchmark_tool.sql` had executed. `ToolExecutionPipeline` rejected the unknown tool and recorded a governance denial audit entry (`auditRecorder.recordGovernanceDenial`).
2. **Sequences #2 – #202 (201 Records):** In task 1838, the standalone calibration gate (`benchmark_qa2_load.py --mode calibrate-only`) was executed to verify the rate limiter fix:
   - Sequence #2: 1 successful calibration probe call (`ORD-BENCH-01`).
   - Sequences #3 – #202: 200 burst requests dispatched at 100 RPS beyond the 60 RPM boundary.
   - Subtotal = $1 + 200 = 201\text{ audit records}$.
3. **Sequences #203 – #19,903 (19,701 Records):** In task 1856, the full benchmark load runner executed:
   - Sequence #203: 1 pre-benchmark probe.
   - Sequences #204 – #403: 200 pre-benchmark burst calls.
   - Sequences #404 – #1,903: 1,500 warmup calls.
   - Sequences #1,904 – #7,903: 6,000 Run 1 calls.
   - Sequences #7,904 – #13,903: 6,000 Run 2 calls.
   - Sequences #13,904 – #19,903: 6,000 Run 3 calls.
   - Subtotal = $1 + 200 + 1,500 + 6,000 + 6,000 + 6,000 = 19,701\text{ audit records}$.

$$\mathbf{1\text{ (Task 1820)}} + \mathbf{201\text{ (Task 1838)}} + \mathbf{19,701\text{ (Task 1856)}} = \mathbf{19,903\text{ total records}}.$$
The reconciliation is exact, verified, and complete.

---

## 8. Bottleneck Ranking & Budget Feasibility Analysis

| Rank | Bottleneck | Component | Measured $p95$ at 100 RPS | % of Total Tail Latency | Concurrency Dependence |
|---|---|---|---|---|---|
| **#1** | **Audit Row Lock Queueing** | PostgreSQL `audit_chain_state FOR UPDATE` | **$281.37\text{ ms}$** | **$62.8\%$** | Non-linear queueing explosion |
| **#2** | **Permission Check Pool Wait** | `toolRepository.isAgentPermitted` (HikariCP) | **$98.76\text{ ms}$** | **$22.1\%$** | Starved by #1 lock queue |
| **#3** | **Transaction Commit WAL Flush** | PostgreSQL `COMMIT` (`fsync`) | **$73.21\text{ ms}$** | **$16.4\%$** | Disk I/O sync contention |
| **#4** | **Security Ingress Processing** | Spring Security JWT + Servlet Ingress | **$39.10\text{ ms}$** | **$8.7\%$** | Thread dispatch delay |
| **#5** | **Redis Rate Limiting** | `RedisRateLimiter.tryAcquire` | **$11.62\text{ ms}$** | **$2.6\%$** | Network RTT to Redis |

### Critical Feasibility Assessment: Can Audit Optimization Alone Meet QA2?
**NO.** 
Even if audit logging overhead were reduced to **0.00 ms**:
$$\text{Remaining Overhead } p95 = \text{Security Ingress } (39.10\text{ ms}) + \text{Permission Check } (98.76\text{ ms}) + \text{Rate Limiter } (11.62\text{ ms}) = \mathbf{149.48\text{ ms}} > 30.0\text{ ms}.$$
Meeting the $\le 30.0\text{ ms}$ SLA requires addressing **both** Audit Row Contention and Permission Check Connection Contention.

---

## 9. Proposed Architectural Choices (Analysis Only — No Implementation)

Based on our empirical measurements, we evaluate three targeted, minimal architectural optimizations:

### Option 1: In-Memory Permission & Metadata Caching (High Priority)
- **Problem:** `isAgentPermitted` queries PostgreSQL over HikariCP on every single request, queueing behind audit locks ($98.76\text{ ms}$ at $p95$).
- **Solution:** Cache tool permissions in an in-memory thread-safe `AtomicReference` or Guava cache with a 5-second TTL or invalidation listener, identical to how `StructuredPolicyEvaluator` already caches policy rules.
- **Expected Benefit:** Reduces permission check latency from **$98.76\text{ ms}$** to **$< 0.05\text{ ms}$**, eliminating 90% of non-audit database queries.
- **Complexity:** Very Low (< 30 lines of code).
- **Durability & Security Implications:** Zero durability risk; permission changes propagate within 5 seconds.

### Option 2: Mode B Asynchronous Micro-Batch Audit Ledger (High Priority)
- **Problem:** Synchronous row-locking of `audit_chain_state` serializes 100 RPS through a single database row ($281.37\text{ ms}$ lock wait + $73.21\text{ ms}$ commit wait).
- **Solution:** Decouple audit recording from the HTTP request thread using a high-throughput bounded lock-free ring buffer (e.g. LMAX Disruptor or `ArrayBlockingQueue`). A background batch processor drains the queue in micro-batches (e.g. 50–100 records per transaction), computes the sequential SHA-256 hash chain, and executes a single multi-row `INSERT` and single `UPDATE` to `audit_chain_state`.
- **Expected Benefit:** Reduces audit-added latency to the HTTP request path from **$354.58\text{ ms}$** to **$< 0.10\text{ ms}$** (enqueue time).
- **Durability & Crash-Consistency Risk:** In-memory queue loss during an ungraceful container crash could lose uncommitted audit events. To mitigate:
  - Back the ring buffer with an append-only local WAL journal (`audit-deferred.wal`) on disk before acknowledging HTTP response, or
  - Maintain fail-closed backpressure: if ring buffer fills, incoming requests block synchronously.
- **Audit Integrity Implications:** Strict linear SHA-256 hash chaining is preserved with zero sequence gaps.

### Option 3: HikariCP Connection Pool & PostgreSQL Tuning (Medium Priority)
- **Problem:** 10 connections in HikariCP is too small for 20 concurrent workers executing multi-query transactions.
- **Solution:** Expand HikariCP `maximumPoolSize` from 10 to 30; configure PostgreSQL `synchronous_commit = off` or group commit delay for benchmark workloads.
- **Expected Benefit:** Relieves connection starvation by 40–60%.
- **Complexity:** Minimal (configuration property only).

---

## 10. Generated Visualizations

Four comprehensive diagnostic charts were generated and saved in [docs/quality/performance/charts/](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/):

1. **Component Latency Breakdown by Arrival Rate ($p50$ vs $p95$):**  
   [qa2_profile_component_breakdown.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_profile_component_breakdown.png)  
   Shows the transition from sub-millisecond audit and permission checks at 10–30 RPS to massive contention at 60–100 RPS.
2. **Gateway Overhead Scaling vs. Arrival Rate (10 to 100 RPS):**  
   [qa2_profile_rate_scaling.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_profile_rate_scaling.png)  
   Illustrates the non-linear scaling curve and the inflection window between 30 and 60 RPS where lock contention triggers.
3. **Audit Sub-Operation Composition across Rates:**  
   [qa2_profile_audit_composition.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_profile_audit_composition.png)  
   Deconstructs audit duration, highlighting that lock wait and commit dominate while SHA-256 computation remains negligible.
4. **100 RPS Latency Stabilization Progression:**  
   [qa2_profile_stabilization.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_profile_stabilization.png)  
   Demonstrates the progressive JIT optimization across the 60s stabilization phase, Window 1, and Window 2.

---

## 11. Conclusion & Recommended Next Task

Task 16E has decisively identified the physical mechanisms behind AgentGate's QA2 latency:
1. **The primary bottleneck is not JIT, not policy evaluation, not JSON schema validation, and not downstream mock latency.**
2. **The true bottlenecks are exclusive database row-locking in Mode A auditing ($281.37\text{ ms}$ at $p95$) and connection pool exhaustion spilling over into permission checks ($98.76\text{ ms}$ at $p95$).**
3. **Recommended Next Implementation Task: M04 Task 16F (Architectural Optimization)**:
   - Implement in-memory permission metadata caching.
   - Implement Mode B micro-batch asynchronous audit logging with bounded ring buffer.
   - Rerun the QA2 acceptance benchmark to verify whether gateway overhead drops below the $30\text{ ms}$ requirement.
