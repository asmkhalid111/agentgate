# AgentGate QA2 Performance Measurement Forensic Audit Report

**Date:** November 23, 2026  
**Audit Scope:** Task 16B QA2 Performance Benchmark Execution  
**Audit Status:** APPROVED — BENCHMARK FORMALLY CLASSIFIED AS **INCONCLUSIVE**  
**Audited Artifacts:**
- `scripts/benchmarks/data/qa2_benchmark_summary.json`
- `scripts/benchmarks/data/qa2_latency_samples.csv`
- `docs/milestones/M04/QA2_PERFORMANCE_REPORT.md`
- `docs/quality/performance/QA2_METHODOLOGY.md`

---

## 1. Executive Summary & Audit Classification

During the M04 Task 16C forensic review, independent audit of the raw benchmark telemetry (`qa2_latency_samples.csv`, 24,000 samples) revealed that the initial QA2 performance benchmark did **not** measure what it purported to measure.

While the original report concluded an authentic **FAIL** ($p95 = 98.96\text{ ms} > 30.0\text{ ms}$), the measurement was contaminated by a configuration defect that caused **98.67% of steady-state requests to be short-circuited at the rate limiter**, combined with load generator blindness to JSON-RPC error payloads over HTTP 200.

The benchmark execution is formally classified as:
$$\mathbf{INCONCLUSIVE}$$

The measured $p95$ of $98.96\text{ ms}$ does not represent valid, successful governed tool execution under the instructor's requirement; it primarily measured the latency of **rate-limit denial evaluation coupled with PostgreSQL audit logging under single-row lock contention**.

---

## 2. Corrected Workload & Request Reconciliation

The comprehensive reconciliation across the 18,000 scheduled steady-state governed requests:

| Category | Count | Percentage | Explanation |
|---|---|---|---|
| **Scheduled Governed Requests** | **18,000** | 100.00% | 3 runs $\times$ 6,000 requests at 100 RPS. |
| **Successful Downstream Executions** | **240** | **1.33%** | Requests that passed rate limiting and invoked downstream `demo-tools` via `RestToolAdapter`. |
| **Quota / Rate-Limit Rejections** | **17,746** | **98.59%** | Short-circuited at `ToolExecutionPipeline` by `rateLimiter.tryAcquire()`; audit-logged as `RATE_LIMIT_EXCEEDED`. |
| **Client Connection Failures** | **14** | **0.08%** | Stale socket resets at the start of Run 1 (`status_code: 0`); never reached the gateway. |
| **Total Requests Without Downstream Execution** | **17,760** | **98.67%** | Sum of quota rejections ($17,746$) and client connection drops ($14$). |

### Audit-Record Exact Reconciliation
The cryptographic verification endpoint `/api/v1/admin/audit/verify` verified **19,497 audit records**:

$$\text{Total Verified Audit Records} = 19,497$$

1. **Pre-Benchmark Calibration Probes:** $11$ records (`DIRECT_EXECUTION`).
2. **Warm-up Phase (30s @ 50 RPS):** $1,500$ records ($60\text{ DIRECT\_EXECUTION} + 1,440\text{ GOVERNANCE\_DENIAL}$).
3. **Governed Run 1:** $5,986$ records ($6,000\text{ attempted} - 14\text{ client drops}$; $120\text{ DIRECT\_EXECUTION} + 5,866\text{ GOVERNANCE\_DENIAL}$).
4. **Governed Run 2:** $6,000$ records ($60\text{ DIRECT\_EXECUTION} + 5,940\text{ GOVERNANCE\_DENIAL}$).
5. **Governed Run 3:** $6,000$ records ($60\text{ DIRECT\_EXECUTION} + 5,940\text{ GOVERNANCE\_DENIAL}$).

$$11 + 1,500 + 5,986 + 6,000 + 6,000 = \mathbf{19,497}$$

---

## 3. Forensic Root Causes

### 3.1 Rate Limit Configuration Precedence Defect
In `apps/gateway/src/main/resources/application.yml`:
```yaml
agentgate:
  ratelimit:
    default-agent-rpm: ${AGENTGATE_RATELIMIT_DEFAULT_AGENT_RPM:60}
    agent-limits:
      agent-finance-bot: 60
      agent-support-bot: 60 # <--- Specific map key
```
In `RateLimitProperties.java`:
```java
public int getAgentRpm(String agentId) {
    return agentLimits.getOrDefault(agentId, defaultAgentRpm);
}
```
In `compose.benchmark.yaml`, the environment variable `AGENTGATE_RATELIMIT_DEFAULT_AGENT_RPM: "12000"` was supplied. However, because `"agent-support-bot"` existed in the `agentLimits` map with value `60`, `getAgentRpm("agent-support-bot")` returned the hardcoded **60 RPM**, completely bypassing the 12,000 default.

As a result:
- In `governed_run1`: Exactly 120 calls were allowed (60 calls across two distinct 60-second minute windows).
- In `governed_run2`: Exactly 60 calls were allowed.
- In `governed_run3`: Exactly 60 calls were allowed.
- Total allowed downstream executions: **240**.

### 3.2 JSON-RPC Error Masking in Load Generator
In `benchmark_qa2_load.py`:
```python
resp = conn.getresponse()
body = resp.read()
# Evaluated only resp.status == 200
```
Spring AI MCP returns JSON-RPC error payloads (`{"jsonrpc":"2.0","error":{"code":-32602,"message":"Rate limit exceeded..."}}`) over **HTTP 200 OK**. Because the test harness checked only the HTTP status code, 17,746 application rejections were erroneously counted as successful responses with $0.00\text{ ms}$ downstream duration.

### 3.3 Stale Client Connection Sockets
The 14 errors at the start of Run 1 were client-side TCP connection drops (`http.client.RemoteDisconnected`). The connections in `gw_pool` had remained idle during the 60-second direct downstream baseline. Tomcat closed the idle connections due to keep-alive timeout, causing the first 14 requests of Run 1 to fail upon dispatch.

---

## 4. Bottleneck Audit: Measured Facts vs. Hypotheses

### 4.1 Directly Measured Facts
1. The gateway server duration for rate-limit rejection + audit append was $p50 = 50.45\text{ ms}$, $p95 = 99.39\text{ ms}$.
2. Direct downstream baseline latency was $p50 = 2.76\text{ ms}$, $p95 = 5.71\text{ ms}$.
3. For the 240 genuine tool executions, downstream duration was $1.91 - 22.14\text{ ms}$ (mean $12.31\text{ ms}$).
4. In all 17,760 rate-limited requests, `auditRecorder.recordGovernanceDenial()` was executed, writing an audit row to PostgreSQL within an independent transaction (`@Transactional(REQUIRES_NEW)`).

### 4.2 Strong Inferences
- The rejection path latency ($50.44\text{ ms}$ median) is dominated by the shared database audit append infrastructure, which acquires `SELECT ... FOR UPDATE` on `audit_chain_state WHERE id = 1`.

### 4.3 Plausible but Unverified Hypotheses
- Attributing exact milliseconds (e.g. "45–55 ms specifically to PostgreSQL WAL fsync") was an unverified hypothesis. The original instrumentation did not measure isolated sub-spans for Redis Lua execution, row-lock wait time, or WAL fsync.

---

## 5. Mandatory Corrective Actions for Task 16D Rerun

1. **Quota Configuration Fix:** Properly override the specific map entry for the benchmark agent in the isolated benchmark stack using `SPRING_APPLICATION_JSON` or equivalent verified mechanism to guarantee effective limits of 12,000 RPM.
2. **Load Generator Response Classification:** Inspect JSON-RPC response bodies for `"error"` or `isError: true` and classify rate-limit rejections, policy denials, and schema failures separately.
3. **Connection Pool Refresh:** Validate and refresh connection pool sockets prior to beginning each measurement phase to prevent idle keep-alive socket drops.
4. **Dedicated Versioning:** Preserve original benchmark artifacts untouched; publish all rerun artifacts under `qa2_v2_` prefixes.
