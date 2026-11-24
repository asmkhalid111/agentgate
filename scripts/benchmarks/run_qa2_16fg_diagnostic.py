#!/usr/bin/env python3
"""
AgentGate — M04 Task 16F-G: Controlled Diagnostic Benchmark Runner
Reuses validated SingleBufferHTTPConnection and phase primitives from run_qa2_final_acceptance.py.
Executes:
  - Phase 0: Calibration probe
  - Phase 1: Multi-rate warm-up & 60s @ 100 RPS stabilization (7,500 calls)
  - Phase 2: One 30-second 100-RPS diagnostic window (3,000 calls)
Evaluates Decision Gate (Case A <= 30ms, Case B > 30ms, Case C invalid).
"""

import json
import os
import subprocess
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from run_qa2_final_acceptance import (
    DATA_DIR,
    DOCKER_BIN,
    GATEWAY_HOST,
    GATEWAY_PORT,
    POSTGRES_CONTAINER,
    ConnectionPool,
    PostgresLockMonitor,
    ResourceMonitor,
    export_csv,
    get_client_token,
    get_user_token,
    init_governed_session,
    run_calibration_probe,
    run_phase,
    summarize_phase,
    verify_audit_chain,
)


def query_pg_wal_checkpoint_stats():
    sql = (
        "SELECT row_to_json(t) FROM (SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, "
        "checkpoint_sync_time, buffers_checkpoint, buffers_backend FROM pg_stat_bgwriter) t; "
        "SELECT row_to_json(w) FROM (SELECT wal_records, wal_bytes, wal_sync, wal_sync_time FROM pg_stat_wal) w;"
    )
    cmd = [DOCKER_BIN, "exec", "-i", POSTGRES_CONTAINER, "psql", "-U", "agentgate", "-d", "agentgate_db", "-t", "-A", "-c", sql]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        if res.returncode == 0:
            lines = [ln.strip() for ln in res.stdout.strip().splitlines() if ln.strip()]
            return {
                "bgwriter": json.loads(lines[0]) if len(lines) >= 1 else {},
                "wal": json.loads(lines[1]) if len(lines) >= 2 else {}
            }
    except Exception as e:
        return {"error": str(e)}
    return {}


def main():
    print("=========================================================================")
    print("AgentGate — M04 Task 16F-G: Controlled Diagnostic Benchmark")
    print("Config: Benchmark INFO logging, Gateway 1024m, Postgres 512m, GC logging")
    print("=========================================================================")

    token = get_client_token("agent-support-bot")
    admin_token = get_user_token("human-admin")

    initial_audit = verify_audit_chain(admin_token)
    print(f"Initial Audit Verification: {initial_audit}")
    if not initial_audit.get("valid"):
        print("CASE C (INCONCLUSIVE): Initial audit chain invalid! Aborting.")
        sys.exit(1)

    initial_pg_stats = query_pg_wal_checkpoint_stats()
    sid = init_governed_session(token)
    print(f"MCP Session initialized: {sid}")

    cal = run_calibration_probe(token, sid)
    print(f"Calibration Gate: {cal}")
    if not cal["valid"]:
        print("CASE C (INCONCLUSIVE): Calibration probe failed! Aborting.")
        sys.exit(1)

    pool = ConnectionPool(GATEWAY_HOST, GATEWAY_PORT, max_size=30)
    lock_monitor = PostgresLockMonitor(interval_sec=0.5)
    res_monitor = ResourceMonitor(interval_sec=2.0)

    lock_monitor.start()
    res_monitor.start()

    warmup_samples = []
    warmup_accounting = {}
    diag_samples = []
    diag_acct = None

    try:
        warmup_specs = [
            ("warmup_10rps", 15.0, 10, 10),
            ("warmup_30rps", 15.0, 30, 15),
            ("warmup_60rps", 15.0, 60, 20),
            ("warmup_stabilization", 60.0, 100, 25)
        ]
        for pname, dur, rps, conc in warmup_specs:
            lock_monitor.set_phase(pname)
            res_monitor.set_phase(pname)
            samples, acct = run_phase(pname, pool, dur, rps, token, sid, concurrency=conc)
            warmup_samples.extend(samples)
            warmup_accounting[pname] = (samples, acct)
            time.sleep(2.0)

        lock_monitor.set_phase("diag_30s_100rps")
        res_monitor.set_phase("diag_30s_100rps")
        diag_samples, diag_acct = run_phase("diag_30s_100rps", pool, 30.0, 100, token, sid, concurrency=25)

    finally:
        lock_monitor.stop()
        res_monitor.stop()
        pool.close_all()

    final_audit = verify_audit_chain(admin_token)
    final_pg_stats = query_pg_wal_checkpoint_stats()
    print(f"\nFinal Audit Verification: {final_audit}")

    export_csv(os.path.join(DATA_DIR, "qa2_16fg_diag_samples.csv"), warmup_samples + diag_samples)

    with open(os.path.join(DATA_DIR, "qa2_16fg_diag_lock_stats.json"), "w", encoding="utf-8") as f:
        json.dump(lock_monitor.samples, f, indent=2)

    with open(os.path.join(DATA_DIR, "qa2_16fg_diag_resource_stats.json"), "w", encoding="utf-8") as f:
        json.dump({
            "container_stats": res_monitor.container_samples,
            "pg_stats_before": initial_pg_stats,
            "pg_stats_after": final_pg_stats
        }, f, indent=2)

    diag_summary = summarize_phase(diag_samples, diag_acct)
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "client_implementation": "SingleBufferHTTPConnection (HTTP/1.1 Keep-Alive, TCP_NODELAY=1)",
        "calibration_probe": cal,
        "initial_audit_verification": initial_audit,
        "final_audit_verification": final_audit,
        "warmup_phases": {p: summarize_phase(s, a) for p, (s, a) in warmup_accounting.items()},
        "diagnostic_window": diag_summary,
        "pg_stats_before": initial_pg_stats,
        "pg_stats_after": final_pg_stats
    }

    with open(os.path.join(DATA_DIR, "qa2_16fg_diag_summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    ov = diag_summary["overhead_ms"]
    au = diag_summary["components_ms"]["audit_total"]
    al = diag_summary["components_ms"]["audit_lock_wait"]
    mc = diag_summary["components_ms"]["ingress_mcp_parse"]

    print("\n=========================================================================")
    print(f"DIAGNOSTIC 30s @ 100 RPS: {diag_acct['successful_executions']}/{diag_acct['planned_requests']} valid "
          f"| achieved_rps={diag_acct['achieved_successful_rps']}")
    print(f"  Overhead: p50={ov['p50']:.2f}ms | p90={ov['p90']:.2f}ms | p95={ov['p95']:.2f}ms | p99={ov['p99']:.2f}ms | max={ov['max']:.2f}ms")
    print(f"  Audit:    p95={au['p95']:.2f}ms | LockWait p95={al['p95']:.2f}ms | MCP Parse p95={mc['p95']:.2f}ms")

    if not final_audit.get("valid") or diag_acct["successful_executions"] != diag_acct["planned_requests"] or diag_acct["achieved_successful_rps"] < 98.0:
        print("DECISION GATE: CASE C (INCONCLUSIVE)")
    elif ov["p95"] <= 30.0:
        print("DECISION GATE: CASE A (ELIGIBLE FOR FULL 18k ACCEPTANCE RERUN)")
    else:
        print("DECISION GATE: CASE B (STILL ABOVE 30ms — STOP FOR REVIEWER ASSESSMENT)")
    print("=========================================================================")


if __name__ == "__main__":
    main()
