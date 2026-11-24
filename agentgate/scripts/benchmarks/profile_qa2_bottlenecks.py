#!/usr/bin/env python3
"""
AgentGate — QA2 Root-Cause Performance Profiling Runner (Task 16E)
Captures granular, non-overlapping nanosecond component durations across increasing arrival rates:
  - 10 RPS (15s = 150 calls)
  - 30 RPS (15s = 450 calls)
  - 60 RPS (15s = 900 calls)
  - Stabilization Phase: 60s @ 100 RPS (6,000 calls)
  - 100 RPS Window 1: 30s @ 100 RPS (3,000 calls)
  - 100 RPS Window 2: 30s @ 100 RPS (3,000 calls)

Monitors:
  - Component-level latency breakdown (Security ingress, Permission, RateLimit, Schema, Policy, Downstream, Audit lock, Audit hash, Audit DB write, Audit commit, PII, Serialization)
  - PostgreSQL row-lock contention on audit_chain_state (pg_stat_activity, pg_locks)
  - Container resource utilization (CPU %, Memory MB)
  - Cryptographic audit chain verification
"""

import argparse
import concurrent.futures
import csv
import http.client
import json
import os
import queue
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT_DIR, "scripts"))
from verify_qa1_security import get_client_token, get_user_token

DATA_DIR = os.path.join(ROOT_DIR, "scripts", "benchmarks", "data")
os.makedirs(DATA_DIR, exist_ok=True)

GATEWAY_HOST = "localhost"
GATEWAY_PORT = 8081
GATEWAY_MCP_URL = f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/mcp"

DOWNSTREAM_HOST = "localhost"
DOWNSTREAM_PORT = 8083
DOWNSTREAM_PATH = "/api/orders/ORD-BENCH-01"

POSTGRES_CONTAINER = "agentgate-benchmark-postgres"

class ConnectionPool:
    def __init__(self, host, port, max_size=25, timeout=10):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.max_size = max_size
        self.pool = queue.Queue(maxsize=max_size)
        for _ in range(max_size):
            self.pool.put(self._create_conn())

    def _create_conn(self):
        return http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def get(self):
        try:
            return self.pool.get(block=True, timeout=10)
        except queue.Empty:
            return self._create_conn()

    def put(self, conn, discard=False):
        if discard:
            try:
                conn.close()
            except Exception:
                pass
            conn = self._create_conn()
        try:
            self.pool.put_nowait(conn)
        except queue.Full:
            try:
                conn.close()
            except Exception:
                pass

    def close_all(self):
        while not self.pool.empty():
            try:
                conn = self.pool.get_nowait()
                conn.close()
            except Exception:
                pass

    def refresh_all(self):
        self.close_all()
        for _ in range(self.max_size):
            self.pool.put(self._create_conn())

class PostgresLockMonitor(threading.Thread):
    """Samples PostgreSQL lock wait events and lock counts during workloads."""
    def __init__(self, interval_sec=1.0):
        super().__init__(daemon=True)
        self.interval_sec = interval_sec
        self.running = True
        self.current_phase = "init"
        self.samples = []

    def set_phase(self, phase):
        self.current_phase = phase

    def run(self):
        docker_bin = "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"
        psql_cmd = [
            docker_bin, "exec", "-i", POSTGRES_CONTAINER,
            "psql", "-U", "agentgate", "-d", "agentgate_db", "-t", "-A", "-c",
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'; "
            "SELECT count(*) FROM pg_locks WHERE relation = 'audit_chain_state'::regclass AND NOT granted; "
            "SELECT count(*) FROM pg_locks WHERE relation = 'audit_chain_state'::regclass;"
        ]
        while self.running:
            try:
                t0 = time.time()
                res = subprocess.run(psql_cmd, capture_output=True, text=True, timeout=5)
                if res.returncode == 0:
                    lines = [line.strip() for line in res.stdout.strip().splitlines() if line.strip()]
                    if len(lines) >= 3:
                        waiting_locks = int(lines[0])
                        waiting_chain_locks = int(lines[1])
                        total_chain_locks = int(lines[2])
                        self.samples.append({
                            "phase": self.current_phase,
                            "timestamp_s": round(t0, 3),
                            "waiting_locks_total": waiting_locks,
                            "waiting_chain_locks": waiting_chain_locks,
                            "total_chain_locks": total_chain_locks
                        })
            except Exception:
                pass
            time.sleep(self.interval_sec)

    def stop(self):
        self.running = False

class ResourceMonitor(threading.Thread):
    def __init__(self, interval_sec=2.0):
        super().__init__(daemon=True)
        self.interval_sec = interval_sec
        self.running = True
        self.samples = []
        self.current_phase = "init"

    def set_phase(self, phase):
        self.current_phase = phase

    def run(self):
        docker_cmd = ["C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe", "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}"]
        while self.running:
            try:
                t0 = time.time()
                res = subprocess.run(docker_cmd, capture_output=True, text=True, timeout=8)
                if res.returncode == 0:
                    for line in res.stdout.strip().splitlines():
                        if "benchmark" in line:
                            parts = line.split(",")
                            if len(parts) >= 3:
                                name = parts[0].strip()
                                cpu_str = parts[1].strip().replace("%", "")
                                mem_parts = parts[2].strip().split("/")
                                mem_str = mem_parts[0].strip()
                                mem_mb = 0.0
                                if "MiB" in mem_str or "MB" in mem_str:
                                    mem_mb = float(mem_str.replace("MiB", "").replace("MB", "").strip())
                                elif "GiB" in mem_str or "GB" in mem_str:
                                    mem_mb = float(mem_str.replace("GiB", "").replace("GB", "").strip()) * 1024.0

                                cpu_val = float(cpu_str) if cpu_str else 0.0
                                self.samples.append({
                                    "phase": self.current_phase,
                                    "timestamp_s": round(t0, 3),
                                    "container_name": name,
                                    "cpu_percent": cpu_val,
                                    "memory_usage_mb": mem_mb
                                })
            except Exception:
                pass
            time.sleep(self.interval_sec)

    def stop(self):
        self.running = False

def init_governed_session(token):
    init_payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "qa2-profiler", "version": "1.0.0"}
        }
    }
    data = json.dumps(init_payload).encode("utf-8")
    req = urllib.request.Request(GATEWAY_MCP_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")
        return sid

def parse_mcp_body(raw: str):
    if not raw:
        return {}
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            try:
                return json.loads(line[5:].strip())
            except Exception:
                pass
    try:
        return json.loads(raw.strip())
    except Exception:
        return {"raw": raw}

def calculate_stats(latencies):
    if not latencies:
        return {"count": 0, "mean": 0, "std": 0, "min": 0, "p50": 0, "p90": 0, "p95": 0, "p99": 0, "max": 0}
    s = sorted(latencies)
    n = len(s)
    mean = sum(s) / n
    variance = sum((x - mean) ** 2 for x in s) / n if n > 1 else 0
    std = variance ** 0.5
    def pct(p):
        k = (n - 1) * (p / 100.0)
        f = int(k)
        c = min(f + 1, n - 1)
        d = k - f
        return s[f] * (1.0 - d) + s[c] * d

    return {
        "count": n,
        "mean": round(mean, 3),
        "std": round(std, 3),
        "min": round(s[0], 3),
        "p50": round(pct(50), 3),
        "p90": round(pct(90), 3),
        "p95": round(pct(95), 3),
        "p99": round(pct(99), 3),
        "max": round(s[-1], 3)
    }

def run_profile_phase(name, pool, duration_sec, target_rps, token, session_id, concurrency=20):
    total_requests = int(duration_sec * target_rps)
    interval = 1.0 / target_rps
    results = []
    print(f"\n[{name.upper()}] Starting: duration={duration_sec}s, target_rps={target_rps}, total_requests={total_requests}, concurrency={concurrency}")

    pool.refresh_all()

    call_payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 100,
        "method": "tools/call",
        "params": {
            "name": "benchmark_read",
            "arguments": {"id": "ORD-BENCH-01"}
        }
    }).encode("utf-8")

    mcp_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": session_id or ""
    }

    lock = threading.Lock()

    def worker(req_idx, dispatch_target_time):
        corr_id = f"{name}-{req_idx:06d}"
        conn = pool.get()
        t_start = time.perf_counter()
        discard = False
        try:
            h = dict(mcp_headers)
            h["X-Request-Correlation-Id"] = corr_id
            conn.request("POST", "/mcp", body=call_payload, headers=h)

            resp = conn.getresponse()
            body = resp.read()
            t_end = time.perf_counter()
            client_lat_ms = (t_end - t_start) * 1000.0

            def parse_header_ms(header_name):
                val = resp.headers.get(header_name)
                return round(float(val) / 1e6, 3) if val else 0.0

            gw_dur_ms = parse_header_ms("X-Gateway-Duration-Ns")
            down_dur_ms = parse_header_ms("X-Downstream-Duration-Ns")
            overhead_ms = parse_header_ms("X-Gateway-Overhead-Ns")

            sec_ingress_ms = parse_header_ms("X-Profile-Security-Ingress-Ns")
            perm_ms = parse_header_ms("X-Profile-Permission-Ns")
            rate_ms = parse_header_ms("X-Profile-RateLimit-Ns")
            schema_ms = parse_header_ms("X-Profile-Schema-Ns")
            policy_ms = parse_header_ms("X-Profile-Policy-Ns")
            circuit_ms = parse_header_ms("X-Profile-Circuit-Ns")
            audit_total_ms = parse_header_ms("X-Profile-Audit-Total-Ns")
            audit_lock_ms = parse_header_ms("X-Profile-Audit-Lock-Ns")
            audit_hash_ms = parse_header_ms("X-Profile-Audit-Hash-Ns")
            audit_write_ms = parse_header_ms("X-Profile-Audit-Write-Ns")
            audit_commit_ms = parse_header_ms("X-Profile-Audit-Commit-Ns")
            pii_ms = parse_header_ms("X-Profile-Pii-Ns")
            serial_ms = parse_header_ms("X-Profile-Serialization-Ns")

            # Classification
            classification = "SUCCESSFUL_GOVERNED_EXECUTION"
            if resp.status != 200:
                classification = f"HTTP_{resp.status}"
            else:
                raw = body.decode("utf-8", errors="replace")
                data = parse_mcp_body(raw)
                if "error" in data:
                    classification = f"JSON_RPC_ERROR_{data['error'].get('message', '')[:30]}"
                elif down_dur_ms <= 0.0:
                    classification = "INVALID_TIMING_ERROR"

            res = {
                "phase": name,
                "request_index": req_idx,
                "timestamp_s": round(t_start - run_start_time, 4),
                "status_code": resp.status,
                "classification": classification,
                "client_latency_ms": round(client_lat_ms, 3),
                "gateway_duration_ms": gw_dur_ms,
                "downstream_duration_ms": down_dur_ms,
                "overhead_ms": overhead_ms,
                "sec_ingress_ms": sec_ingress_ms,
                "permission_ms": perm_ms,
                "rate_limit_ms": rate_ms,
                "schema_ms": schema_ms,
                "policy_ms": policy_ms,
                "circuit_ms": circuit_ms,
                "audit_total_ms": audit_total_ms,
                "audit_lock_ms": audit_lock_ms,
                "audit_hash_ms": audit_hash_ms,
                "audit_write_ms": audit_write_ms,
                "audit_commit_ms": audit_commit_ms,
                "pii_ms": pii_ms,
                "serialization_ms": serial_ms,
                "correlation_id": corr_id
            }
        except Exception as e:
            t_end = time.perf_counter()
            discard = True
            res = {
                "phase": name,
                "request_index": req_idx,
                "timestamp_s": round(t_start - run_start_time, 4),
                "status_code": 0,
                "classification": "CONNECTION_EXCEPTION",
                "client_latency_ms": round((t_end - t_start) * 1000.0, 3),
                "gateway_duration_ms": 0.0,
                "downstream_duration_ms": 0.0,
                "overhead_ms": 0.0,
                "sec_ingress_ms": 0.0,
                "permission_ms": 0.0,
                "rate_limit_ms": 0.0,
                "schema_ms": 0.0,
                "policy_ms": 0.0,
                "circuit_ms": 0.0,
                "audit_total_ms": 0.0,
                "audit_lock_ms": 0.0,
                "audit_hash_ms": 0.0,
                "audit_write_ms": 0.0,
                "audit_commit_ms": 0.0,
                "pii_ms": 0.0,
                "serialization_ms": 0.0,
                "correlation_id": corr_id,
                "error": str(e)
            }
        finally:
            pool.put(conn, discard=discard)

        with lock:
            results.append(res)
            if len(results) % 500 == 0 or len(results) == total_requests:
                print(f"  [{name}] Completed {len(results)}/{total_requests} requests...")

    executor = concurrent.futures.ThreadPoolExecutor(max_workers=concurrency)
    run_start_time = time.perf_counter()

    for i in range(total_requests):
        target_t = run_start_time + i * interval
        now = time.perf_counter()
        sleep_dur = target_t - now
        if sleep_dur > 0:
            time.sleep(sleep_dur)
        executor.submit(worker, i, target_t)

    executor.shutdown(wait=True)
    actual_duration = time.perf_counter() - run_start_time
    actual_rps = len(results) / actual_duration if actual_duration > 0 else 0
    print(f"[{name.upper()}] Done in {actual_duration:.2f}s ({actual_rps:.1f} RPS actual).")
    return results, actual_duration, actual_rps

def verify_single_probe(token, sid):
    print("\n--- Diagnostic Gate: Single Profiling Probe ---")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": sid
    }
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 9999,
        "method": "tools/call",
        "params": {
            "name": "benchmark_read",
            "arguments": {"id": "ORD-BENCH-01"}
        }
    }).encode("utf-8")

    req = urllib.request.Request(GATEWAY_MCP_URL, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read()
            raw = body.decode("utf-8", errors="replace")
            print(f"Probe HTTP Status: {resp.status}")
            h = dict(resp.headers)
            def p(name):
                v = h.get(name.lower()) or h.get(name)
                return f"{float(v)/1e6:.2f}ms" if v else "N/A"

            print(f"  Total Duration:     {p('X-Gateway-Duration-Ns')}")
            print(f"  Downstream:         {p('X-Downstream-Duration-Ns')}")
            print(f"  Gateway Overhead:   {p('X-Gateway-Overhead-Ns')}")
            print(f"  Security Ingress:   {p('X-Profile-Security-Ingress-Ns')}")
            print(f"  Permission Check:   {p('X-Profile-Permission-Ns')}")
            print(f"  Rate Limiter:       {p('X-Profile-RateLimit-Ns')}")
            print(f"  Schema Validation:  {p('X-Profile-Schema-Ns')}")
            print(f"  Policy Evaluator:   {p('X-Profile-Policy-Ns')}")
            print(f"  Circuit Breaker:    {p('X-Profile-Circuit-Ns')}")
            print(f"  Audit Total:        {p('X-Profile-Audit-Total-Ns')}")
            print(f"    - Lock Wait:      {p('X-Profile-Audit-Lock-Ns')}")
            print(f"    - Hash Calc:      {p('X-Profile-Audit-Hash-Ns')}")
            print(f"    - DB Write:       {p('X-Profile-Audit-Write-Ns')}")
            print(f"    - DB Commit:      {p('X-Profile-Audit-Commit-Ns')}")
            print(f"  PII & Truncation:   {p('X-Profile-Pii-Ns')}")
            print(f"  MCP Serialization:  {p('X-Profile-Serialization-Ns')}")

            if "X-Profile-Audit-Total-Ns".lower() not in [k.lower() for k in h.keys()]:
                print("[ERROR] Profiling headers missing from response!")
                return False
            return True
    except Exception as e:
        print(f"[ERROR] Single probe failed: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="AgentGate QA2 Root-Cause Performance Profiler")
    parser.add_argument("--prefix", default="qa2_profile", help="Prefix for output data files")
    args = parser.parse_args()

    print("=================================================================")
    print("AgentGate QA2 Root-Cause Performance Profiler (Task 16E/16F)")
    print(f"Capturing component-level telemetry across 10, 30, 60, 100 RPS [prefix: {args.prefix}]")
    print("=================================================================")

    token = get_client_token("agent-support-bot")
    admin_token = get_user_token("human-admin")
    sid = init_governed_session(token)
    print(f"MCP Session initialized: {sid}")

    # Verify single probe
    ok = verify_single_probe(token, sid)
    if not ok:
        print("FATAL: Profiling instrumentation verification failed! Aborting.")
        sys.exit(1)

    pool = ConnectionPool(GATEWAY_HOST, GATEWAY_PORT, max_size=25)
    lock_monitor = PostgresLockMonitor(interval_sec=0.5)
    res_monitor = ResourceMonitor(interval_sec=2.0)

    lock_monitor.start()
    res_monitor.start()

    all_samples = []
    phase_metrics = {}

    try:
        # Phase 1: 10 RPS (15s = 150 calls)
        lock_monitor.set_phase("profile_10rps")
        res_monitor.set_phase("profile_10rps")
        p10_res, p10_dur, p10_rps = run_profile_phase("profile_10rps", pool, 15.0, 10, token, sid, concurrency=10)
        all_samples.extend(p10_res)
        time.sleep(2.0)

        # Phase 2: 30 RPS (15s = 450 calls)
        lock_monitor.set_phase("profile_30rps")
        res_monitor.set_phase("profile_30rps")
        p30_res, p30_dur, p30_rps = run_profile_phase("profile_30rps", pool, 15.0, 30, token, sid, concurrency=15)
        all_samples.extend(p30_res)
        time.sleep(2.0)

        # Phase 3: 60 RPS (15s = 900 calls)
        lock_monitor.set_phase("profile_60rps")
        res_monitor.set_phase("profile_60rps")
        p60_res, p60_dur, p60_rps = run_profile_phase("profile_60rps", pool, 15.0, 60, token, sid, concurrency=20)
        all_samples.extend(p60_res)
        time.sleep(2.0)

        # Phase 4: Extended Stabilization Phase at 100 RPS (60s = 6,000 calls)
        # Allows JIT compiler to optimize SpEL, Jackson, and HikariCP connection pool
        lock_monitor.set_phase("profile_stabilization")
        res_monitor.set_phase("profile_stabilization")
        p_stab_res, _, _ = run_profile_phase("profile_stabilization", pool, 60.0, 100, token, sid, concurrency=20)
        # Note: stabilization samples are recorded in all_samples for timeseries/progression analysis
        all_samples.extend(p_stab_res)
        time.sleep(2.0)

        # Phase 5: 100 RPS Window 1 (30s = 3,000 calls)
        lock_monitor.set_phase("profile_100rps_win1")
        res_monitor.set_phase("profile_100rps_win1")
        p100_w1_res, p100_w1_dur, p100_w1_rps = run_profile_phase("profile_100rps_win1", pool, 30.0, 100, token, sid, concurrency=20)
        all_samples.extend(p100_w1_res)
        time.sleep(2.0)

        # Phase 6: 100 RPS Window 2 (30s = 3,000 calls)
        lock_monitor.set_phase("profile_100rps_win2")
        res_monitor.set_phase("profile_100rps_win2")
        p100_w2_res, p100_w2_dur, p100_w2_rps = run_profile_phase("profile_100rps_win2", pool, 30.0, 100, token, sid, concurrency=20)
        all_samples.extend(p100_w2_res)

    finally:
        lock_monitor.stop()
        res_monitor.stop()
        pool.close_all()

    # Post-profile audit verification
    print("\nVerifying cryptographic audit chain...")
    audit_req = urllib.request.Request(f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/api/v1/admin/audit/verify", method="POST")
    audit_req.add_header("Authorization", f"Bearer {admin_token}")
    audit_req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(audit_req, timeout=30) as resp:
        audit_ver = json.loads(resp.read().decode("utf-8"))
        print(f"Audit verification result: {audit_ver}")

    # Export CSV samples
    samples_path = os.path.join(DATA_DIR, f"{args.prefix}_samples.csv")
    print(f"\nExporting {samples_path} ({len(all_samples)} rows)...")
    with open(samples_path, "w", newline="", encoding="utf-8") as f:
        fieldnames = [
            "phase", "request_index", "timestamp_s", "status_code", "classification",
            "client_latency_ms", "gateway_duration_ms", "downstream_duration_ms", "overhead_ms",
            "sec_ingress_ms", "permission_ms", "rate_limit_ms", "schema_ms", "policy_ms", "circuit_ms",
            "audit_total_ms", "audit_lock_ms", "audit_hash_ms", "audit_write_ms", "audit_commit_ms",
            "pii_ms", "serialization_ms", "correlation_id"
        ]
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for s in all_samples:
            writer.writerow(s)

    # Export lock stats
    lock_stats_path = os.path.join(DATA_DIR, f"{args.prefix}_lock_stats.json")
    with open(lock_stats_path, "w", encoding="utf-8") as f:
        json.dump(lock_monitor.samples, f, indent=2)

    # Compute detailed summary per phase
    summary = {
        "profiling_timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "phases": {},
        "audit_verification": audit_ver
    }

    evaluated_phases = [
        ("profile_10rps", p10_res, p10_dur, p10_rps),
        ("profile_30rps", p30_res, p30_dur, p30_rps),
        ("profile_60rps", p60_res, p60_dur, p60_rps),
        ("profile_stabilization", p_stab_res, 60.0, len(p_stab_res) / 60.0),
        ("profile_100rps_win1", p100_w1_res, p100_w1_dur, p100_w1_rps),
        ("profile_100rps_win2", p100_w2_res, p100_w2_dur, p100_w2_rps)
    ]

    for p_name, p_samples, p_dur, p_rps in evaluated_phases:
        valid = [s for s in p_samples if s["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]
        def st(key):
            return calculate_stats([s[key] for s in valid])

        summary["phases"][p_name] = {
            "total_requests": len(p_samples),
            "successful_requests": len(valid),
            "actual_duration_s": round(p_dur, 2),
            "actual_rps": round(p_rps, 2),
            "client_latency_ms": st("client_latency_ms"),
            "gateway_duration_ms": st("gateway_duration_ms"),
            "downstream_duration_ms": st("downstream_duration_ms"),
            "overhead_ms": st("overhead_ms"),
            "components_ms": {
                "security_ingress": st("sec_ingress_ms"),
                "permission_check": st("permission_ms"),
                "rate_limiter": st("rate_limit_ms"),
                "schema_validation": st("schema_ms"),
                "policy_evaluation": st("policy_ms"),
                "circuit_breaker": st("circuit_ms"),
                "downstream_adapter": st("downstream_duration_ms"),
                "audit_total": st("audit_total_ms"),
                "audit_lock_wait": st("audit_lock_ms"),
                "audit_hash_calc": st("audit_hash_ms"),
                "audit_db_write": st("audit_write_ms"),
                "audit_commit": st("audit_commit_ms"),
                "pii_and_truncation": st("pii_ms"),
                "serialization": st("serialization_ms")
            }
        }

    summary_path = os.path.join(DATA_DIR, f"{args.prefix}_summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    # Also keep standard qa2_profile_summary.json updated if prefix != qa2_profile
    if args.prefix != "qa2_profile":
        std_path = os.path.join(DATA_DIR, "qa2_profile_summary.json")
        with open(std_path, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)

    print(f"Summary exported to {summary_path}")

    # Print high-level comparison table to console
    print("\n=========================================================================================")
    print(f"{'Phase':<22} | {'RPS':<6} | {'Overhead p50':<12} | {'Overhead p95':<12} | {'Audit Lock p95':<14} | {'Downstream p50':<14}")
    print("-----------------------------------------------------------------------------------------")
    for p_name in ["profile_10rps", "profile_30rps", "profile_60rps", "profile_100rps_win1", "profile_100rps_win2"]:
        p_data = summary["phases"][p_name]
        rps = p_data["actual_rps"]
        o_p50 = p_data["overhead_ms"]["p50"]
        o_p95 = p_data["overhead_ms"]["p95"]
        a_lock_p95 = p_data["components_ms"]["audit_lock_wait"]["p95"]
        d_p50 = p_data["downstream_duration_ms"]["p50"]
        print(f"{p_name:<22} | {rps:<6.1f} | {o_p50:<12.2f} | {o_p95:<12.2f} | {a_lock_p95:<14.2f} | {d_p50:<14.2f}")
    print("=========================================================================================")

if __name__ == "__main__":
    main()
