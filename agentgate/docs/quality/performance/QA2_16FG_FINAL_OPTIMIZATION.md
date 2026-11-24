# AgentGate — Task 16F-G: Final Bounded Audit Latency Experiment

**Document Reference:** `docs/quality/performance/QA2_16FG_FINAL_OPTIMIZATION.md`  
**Milestone:** M04 Performance Optimization — Task 16F-G (Final Bounded Experiment)  
**Author:** AgentGate Architecture & Diagnostic Agent  
**Date:** 2026-11-25  
**Baseline Git Commit:** `aa0a67f217634e31746f04e238c8475f08492832`  
**Decision Gate Outcome:** **CASE C / INCONCLUSIVE (and > 30 ms)** — 18k Acceptance Rerun **Not Executed**; **QA2 Remains FAIL**

---

## 1. Executive Summary

Following Task 16F-F, the external architecture reviewer authorized a strictly bounded benchmark-environment experiment (**Options A + Limited C**) without modifying `AuditRecorder`, `JdbcAuditRepository`, transaction boundaries, SQL, or security/durability guarantees:
1. **Logging:** Overrode `com.agentgate.gateway: INFO`, `org.springframework.security: WARN`, and `org.springframework.ai.mcp: INFO` in `application-benchmark.yml` only.
2. **Container Memory Limits:** Increased `benchmark-gateway` `mem_limit` from `512m` to `1024m` and `benchmark-postgres` `mem_limit` from `256m` to `512m` in `compose.benchmark.yaml` (verified host Docker Desktop capacity: `8 CPUs`, `7.60 GiB` RAM).
3. **Observability:** Enabled bounded JVM GC logging (`JAVA_TOOL_OPTIONS: "-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=2,filesize=10m"`) and non-destructive PostgreSQL `pg_stat_bgwriter` / `pg_stat_wal` inspection.

### Key Result
Environment-only adjustments **did not** resolve the 100-RPS audit contention bottleneck:
- **30s Diagnostic Window @ 100 Offered RPS:** `3,000 / 3,000` valid executions, `achieved_rps = 97.96` (`drain = 30.62 s`, `lag_p95 = 2,811.05 ms`).
- **Gateway-Added Overhead:** `p50 = 132.55 ms`, **`p95 = 723.12 ms`**, `p99 = 1,117.20 ms`, `max = 2,506.09 ms`.
- **Audit Subsystem:** `audit_total p95 = 701.90 ms`, `audit_lock_wait p95 = 686.28 ms`.
- **MCP Parse ($T_2 \to T_3$):** `p95 = 3.37 ms` (client single-buffer transport remains fast).
- **Decision Gate:** Because `achieved_rps = 97.96 < 98.0` and `p95 = 723.12 ms > 30.0 ms`, the mandatory decision gate halted execution before the 18,000-call acceptance suite. **QA2 remains FAIL.**

---

## 2. Effective Configuration Comparison

| Setting | Before (Task 16F-E) | After (Task 16F-G Benchmark Only) |
| :--- | :--- | :--- |
| `logging.level.com.agentgate.gateway` | `DEBUG` (inherited from `application.yml`) | `INFO` (`application-benchmark.yml`) |
| `logging.level.org.springframework.security` | `DEBUG` (inherited from `application.yml`) | `WARN` (`application-benchmark.yml`) |
| `logging.level.org.springframework.ai.mcp` | `DEBUG` (inherited from `application.yml`) | `INFO` (`application-benchmark.yml`) |
| `benchmark-gateway` `mem_limit` | `512m` | `1024m` |
| `benchmark-postgres` `mem_limit` | `256m` | `512m` |
| JVM GC Logging | Disabled | `-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=2,filesize=10m` |
| Production / Dev Profiles | Unchanged | Unchanged |

---

## 3. Diagnostic Workload Results (`qa2_16fg_`)

- **Client:** `SingleBufferHTTPConnection` (HTTP/1.1 persistent keep-alive, `TCP_NODELAY=1`, `concurrency=25`).
- **Calibration Gate:** `200 OK`, `valid = true`, `tcp_nodelay = 1`, `overhead = 219.83 ms` (cold first-request JIT).

| Phase | Valid / Planned | Achieved RPS | Drain Dur | Lag p95 | Overhead p50 | **Overhead p95** | Audit Total p95 | Lock Wait p95 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `warmup_10rps` | 150 / 150 | 10.04 | 14.94 s | 8.72 ms | 28.08 ms | 115.65 ms | — | — |
| `warmup_30rps` | 450 / 450 | 29.97 | 15.01 s | 16.80 ms | 15.10 ms | 57.58 ms | — | — |
| `warmup_60rps` | 900 / 900 | 59.92 | 15.02 s | 563.23 ms | 19.65 ms | 396.32 ms | — | — |
| `warmup_stabilization` | 6,000 / 6,000 | 78.17 | 76.76 s | 16,106.67 ms | 535.95 ms | 1,093.75 ms | — | — |
| **`diag_30s_100rps`** | **3,000 / 3,000** | **97.96** | **30.62 s** | **2,811.05 ms** | **132.55 ms** | **723.12 ms** | **701.90 ms** | **686.28 ms** |

---

## 4. Audit, GC, PostgreSQL WAL, and Resource Evidence

### 4.1 Cryptographic Audit Chain Verification
- **Initial Sequence:** `55,811` (`valid: true`, `corruptedSequence: null`)
- **Final Sequence:** **`66,312`** (`valid: true`, `corruptedSequence: null`)
- **Reconciliation:** `55,811 + 1 (calibration) + 7,500 (warm-up) + 3,000 (diagnostic) = 66,312` (100% exact).

### 4.2 PostgreSQL Lock, Checkpoint & WAL Telemetry
- **Lock Contention (`pg_locks` 0.5s polling):**
  - `warmup_10rps` & `warmup_30rps`: `0` waiting locks.
  - `warmup_60rps`: `max_waiting = 15`, `mean_waiting = 6.44` (8/16 samples non-zero).
  - `warmup_stabilization`: `max_waiting = 23`, `mean_waiting = 17.13` (66/69 samples non-zero).
  - `diag_30s_100rps`: `max_waiting = 21`, `mean_waiting = 14.60` (18/20 samples non-zero).
- **Checkpoints (`pg_stat_bgwriter`):** `checkpoints_timed: 4 -> 4`, `checkpoints_req: 5 -> 5` (**0 checkpoints occurred during the run**, ruling out checkpoints as the cause of the sustained contention).
- **WAL Activity (`pg_stat_wal`):** `wal_sync` increased from `56,028` to `66,506` (**+10,478 synchronous WAL `fsync()` operations** across 10,501 transactions), generating `16.6 MB` of WAL (`83.87 MB -> 100.47 MB`).

### 4.3 JVM GC & Container Telemetry
- **Container `JAVA_OPTS`:** `-Xmx512m -Xms256m -XX:+UseSerialGC` (single-threaded SerialGC inside the image).
- **GC Log (`/tmp/gc.log` -> `qa2_16fg_gc.log`):** `85` pauses total (`sum = 2,262.27 ms`, `mean = 26.61 ms`, `max = 314.25 ms` during metadata threshold expansion at `t=79.6s`).
- **Container `docker stats`:**
  - `agentgate-benchmark-gateway`: CPU mean `185.5%`, max `369.2%`; Mem max `496.6 MB` (out of `1024 MB`).
  - `agentgate-benchmark-postgres`: CPU mean `71.5%`, max `134.1%`; Mem max `117.1 MB` (out of `512 MB`).
  - `agentgate-benchmark-demo-tools`: CPU mean `41.4%`, max `110.6%`; Mem max `157.6 MB`.
  - `agentgate-benchmark-redis`: CPU mean `7.9%`, max `22.9%`; Mem max `8.7 MB`.

### 4.4 Limitations
- Actual PostgreSQL `COMMIT` / `fsync()` duration cannot be isolated from `HikariCP.getConnection()` and `scrubArguments()` without modifying `AuditRecorder` (excluded from Task 16F-G scope).
- `pg_stat_wal.wal_sync_time` reported `0` because `track_wal_io_timing` is disabled by default in PostgreSQL 16.

---

## 5. Conclusion & Recommendation

1. **Effect of Environment Changes:** Reducing logging to `INFO`/`WARN` and doubling container memory limits (`1024m`/`512m`) did **not** eliminate 100-RPS latency contention; `p95` remained `723.12 ms` (`audit_lock_wait p95 = 686.28 ms`).
2. **Confirmed Fundamental Bottleneck:** The latency ceiling breach is caused by **strictly serialized single-row `FOR UPDATE` locking on `audit_chain_state`** combined with **one synchronous PostgreSQL transaction + WAL `fsync()` per tool call** (`+10,478` WAL syncs for `10,501` calls) on virtualized Docker Desktop storage.
3. **Final QA2 Status:** **FAIL** (Diagnostic halted at Case C / >30 ms; 18,000-call rerun skipped per protocol).
4. **Recommendation:** **Close the performance optimization investigation** and proceed to project submission with this explicit documented architectural trade-off: AgentGate prioritizes **100% fail-closed, synchronous, tamper-evident SHA-256 audit durability** (66,312 / 66,312 verified records) over sub-30 ms p95 tail latency at 100 RPS on single-node Docker Desktop PostgreSQL.

---

## 6. Evidence Locations
- [qa2_16fg_diag_samples.csv](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_16fg_diag_samples.csv)
- [qa2_16fg_diag_summary.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_16fg_diag_summary.json)
- [qa2_16fg_diag_lock_stats.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_16fg_diag_lock_stats.json)
- [qa2_16fg_diag_resource_stats.json](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_16fg_diag_resource_stats.json)
- [qa2_16fg_gc.log](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/data/qa2_16fg_gc.log)
- [run_qa2_16fg_diagnostic.py](file:///e:/Codes/Software-Architecture-Project/agentgate/scripts/benchmarks/run_qa2_16fg_diagnostic.py)
