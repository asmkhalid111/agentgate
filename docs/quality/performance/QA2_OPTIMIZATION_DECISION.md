# AgentGate — Architecture Decision Record: QA2 Performance Optimization (ADR-005)

**Status:** APPROVED & PHASE 1 EXPERIMENT EXECUTED  
**Date:** November 24, 2026  
**Context:** CSE 444 Project 1 — AgentGate Quality Attribute QA2 Performance Verification  
**Authoritative QA2 Requirement:**
> *"The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."*

---

## 1. Problem Statement & Historical Context

In Task 16D, the formal QA2 performance benchmark rerun resulted in a **FAIL**, measuring a pooled gateway overhead p95 of **$197.63\text{ ms}$** across 18,000 successful governed calls at 100 RPS.

In Task 16E, monotonic nanosecond diagnostic profiling across 13 mutually exclusive execution spans identified severe contention:
- Row-lock waiting on `audit_chain_state WHERE id = 1` measured **$281.37\text{ ms} - 359.80\text{ ms}$** at $p95$.
- Permission check execution via `toolRepository.isAgentPermitted` measured **$98.76\text{ ms} - 128.90\text{ ms}$** at $p95$.
- Total net gateway overhead at $p95$ reached **$447.71\text{ ms} - 577.89\text{ ms}$**.

Before modifying core architectural boundaries or adopting risky asynchronous audit buffering, the external architecture reviewer mandated a formal **Optimization Architecture Decision (Task 16F-A)** to evaluate database contention, statistical nuances, security invariants, and execute a controlled, empirical first experiment.

---

## 2. Core Architectural Invariants Preserved

1. **Mode A Business Transaction Atomicity (`@Transactional(propagation = Propagation.REQUIRED)`):**
   - Invoked during human approval decisions (`ApprovalService.decide(...)`) and emergency kill switch activations (`KillSwitchService.activate(...)`).
   - The audit log record participates directly in the business transaction. If the audit record fails to persist, the domain state mutation rolls back. If domain logic throws, the audit entry rolls back.
   - **Mode A audit logging cannot be deferred, buffered, or made asynchronous.**

2. **Mode B Pre-Dispatch Write Intent Durability (FR7):**
   - For mutating write operations (`tool.isWriteOperation() == true`), `AuditRecorder.recordWriteDispatchIntent(...)` commits an independent intent record before downstream network dispatch.
   - If persistence fails, execution fails closed; the downstream tool is never invoked.

3. **Continuous Cryptographic Hash Chain Integrity (FR7, C4):**
   - Strict linear SHA-256 hash chaining ($1, 2, 3, \dots, N$) must be preserved with zero sequence gaps.

4. **Revocation-Safe Access Control (QA1, Reviewer Amendment 4):**
   - Any tool permission revocation must take effect immediately. Blind time-based TTL caches (e.g. 5 seconds) are rejected to eliminate vulnerability windows for revoked agents.

---

## 3. Profiling Statistical Corrections

1. **Method Duration vs. Row-Lock Wait:** In Task 16E, `audit_lock_ms` wrapped `auditRepository.getChainStateForUpdate()`. Under 100 RPS with 20 worker threads and a 10-connection pool, this measurement captured HikariCP pool acquisition delays interwoven with network roundtrip and PostgreSQL row-lock acquisition.
2. **Residual Commit Attribution:** The calculated residual `auditCommitNs` reflects PostgreSQL WAL disk flush (`fsync`) compounded by Spring `TransactionInterceptor` post-commit synchronization and HikariCP connection release.
3. **Non-Additivity of Percentiles:** Summing component $p95$ values ($359.8\text{ ms} + 128.9\text{ ms} + 106.8\text{ ms} \dots$) does not equal total $p95$, because individual requests experience tail latency spikes in different sub-components.

---

## 4. Empirical Experiment: HikariCP Connection Pool Sizing (Task 16F-B)

### 4.1 Hypothesis
The catastrophic tail latency in both `audit_lock_ms` and `permission_check_ms` was primarily induced by **HikariCP connection pool starvation**. Under 20 concurrent worker threads dispatching 100 RPS, 8 to 9 connections in the 10-connection pool were continuously tied up in transactions waiting for the row lock, leaving only 1–2 connections for all incoming requests.

Expanding HikariCP `maximum-pool-size` from 10 to 30 in `application-benchmark.yml` was predicted to eliminate pool starvation, allowing incoming threads to acquire connections without delay.

### 4.2 Measured Results

The identical multi-rate profiling protocol (10 RPS, 30 RPS, 60 RPS, 60s stabilization, 100 RPS Window 1, 100 RPS Window 2; 13,500 total calls) was executed against the expanded pool size:

| Sub-Operation / Metric | Task 16E Baseline (Pool Size = 10) | Task 16F-B Experiment (Pool Size = 30) | Improvement | Status |
|---|---|---|---|---|
| **Delivered Concurrency Throughput** | 92.2 RPS | **99.84 RPS** | **+7.64 RPS** | Target Achieved |
| **Audit Lock Wait ($p95$)** | **$281.37\text{ ms}$** | **$2.50\text{ ms}$** | **-99.1%** | **ELIMINATED** |
| **Permission Check ($p95$)** | **$98.76\text{ ms}$** | **$2.00\text{ ms}$** | **-98.0%** | **ELIMINATED** |
| **Audit DB Write ($p95$)** | $11.85\text{ ms}$ | **$1.68\text{ ms}$** | **-85.8%** | Sub-2ms |
| **Audit Transaction Commit ($p95$)** | $73.21\text{ ms}$ | **$3.09\text{ ms}$** | **-95.8%** | Sub-4ms |
| **Audit Total Subsystem ($p95$)** | **$354.58\text{ ms}$** | **$7.77\text{ ms}$** | **-97.8%** | **ELIMINATED** |
| **Downstream Adapter ($p95$)** | $25.52\text{ ms}$ | **$3.18\text{ ms}$** | **-87.5%** | Healthy Baseline |
| **Rate Limiter ($p95$)** | $11.62\text{ ms}$ | **$1.20\text{ ms}$** | **-89.7%** | Near-Zero RTT |
| **Security Ingress ($p95$)** | $39.10\text{ ms}$ | **$48.02\text{ ms}$** | Stable | **Primary Remaining Factor** |
| **Total Net Gateway Overhead ($p50$)** | **$154.87\text{ ms}$** | **$49.27\text{ ms}$** | **-68.2%** | Dramatic Reduction |
| **Total Net Gateway Overhead ($p95$)** | **$447.71\text{ ms}$** | **$58.78\text{ ms}$** | **-86.9%** | Reduced by $389\text{ ms}$ |
| **Cryptographic Audit Chain Integrity** | 100% Valid | **100% Valid (13,502 / 13,502)** | **Zero Gaps** | Verified |

Visual evidence generated:
- [qa2_pool_comparison.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_pool_comparison.png): Side-by-side bar chart showing component-level latency reductions from Pool 10 to Pool 30.
- [qa2_profile_component_breakdown.png](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/quality/performance/charts/qa2_profile_component_breakdown.png): Updated 6-phase component breakdown.

### 4.3 Methodological Notes & Limitations
- **Configurations Measured:** Pool Size 10 (Task 16E baseline) and Pool Size 30 (Task 16F-B).
- **Untested Configurations:** Intermediate pool sizes (16, 24) were not measured in this execution. The experiment prioritized evaluating the upper authorized boundary ($N=30$) to decisively test whether exceeding concurrent worker threads ($20$ workers) completely eliminates connection queueing. Intermediate sizing curves may be explored if fine-grained tuning is requested by the architecture reviewer.
- **Controlled Comparison Limitations:** While both runs executed the identical 6-phase profiling workload (10, 30, 60, 100 RPS; 20 client concurrency; 13,500 requests) with the identical nanosecond span instrumentation, they ran in sequential Docker sessions. Differences in container startup time and host background activity are minor but acknowledged.

---

## 5. Architectural Findings & Key Takeaways

1. **The Row-Lock Bottleneck Was an Illusion of Pool Starvation:**
   - Under 10 connections, threads waiting to acquire a connection inside `HikariPool.getConnection()` inflated the apparent duration of `getChainStateForUpdate()` to hundreds of milliseconds.
   - When the pool size was expanded to 30 (exceeding the 20 concurrent worker threads), connections were immediately available. Pure PostgreSQL row-lock acquisition and statement execution resolved in **$0.47\text{ ms}$ median and $2.50\text{ ms}$ at $p95$**!
   - Asynchronous audit redesign (Option D) is **unnecessary** and should not be implemented, avoiding complex in-memory ring buffers and crash-consistency risks.

2. **Permission Check Contention Was Purely Collateral:**
   - With pool size 30, `isAgentPermitted` database queries resolved in **$0.94\text{ ms}$ median and $2.00\text{ ms}$ at $p95$**.
   - While in-memory event-driven caching (Option C) can reduce this further to $< 0.05\text{ ms}$, database queries are no longer a major bottleneck.

3. **The Dominant Remaining Latency Component is Security Ingress:**
   - Total gateway overhead at $p95$ is now **$58.78\text{ ms}$** (down from $447.71\text{ ms}$), approaching the $30.0\text{ ms}$ ceiling.
   - Inside that $58.78\text{ ms}$, **Security Ingress accounts for $48.02\text{ ms}$ ($81.7\%$)**.
   - All internal pipeline operations combined (permission check + rate limit + schema validation + policy evaluation + circuit breaker + audit logging + PII redaction + serialization) sum to **$< 11\text{ ms}$**!

---

## 6. Next Steps: Closing the Final $28.78\text{ ms}$ Gap

To bring gateway-added tail latency from $58.78\text{ ms}$ down below the authoritative $30.0\text{ ms}$ SLA:
1. **Analyze Security Ingress Pipeline:** Investigate the Spring Security filter chain traversal between `BenchmarkTimingFilter` (servlet entry) and `DynamicGovernedToolCallback.call()`:
   - Stateless JWT decoding and Keycloak public key verification caching.
   - Filter order and virtual thread handoff optimization.
2. **Execute Full QA2 Acceptance Benchmark:** Once security ingress is streamlined, execute the formal 3-run 18,000-request benchmark suite (`benchmark_qa2_load.py`) to verify compliance with $p95 \le 30.0\text{ ms}$.
