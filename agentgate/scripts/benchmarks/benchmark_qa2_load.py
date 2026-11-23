#!/usr/bin/env python3
"""
AgentGate — QA2 Performance Benchmark Load Runner (v2 Validated)
Authoritative QA2 Requirement:
  "The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits."

Protocol:
  1. Calibration Gate (pre-benchmark): Verify non-zero downstream duration and 200-call burst beyond 60 RPM.
  2. Warmup: 30s at 50 RPS to stabilize JVM C2 JIT, HikariCP, Redis, JWKS cache.
  3. Direct Baseline: 60s at 100 RPS (6,000 requests) to downstream mock service.
  4. Governed Steady-State: 3 distinct runs of 60s at 100 RPS (3 x 6,000 = 18,000 requests) to governed gateway.
  5. Post-benchmark audit cryptographic verification.
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

class ResourceMonitor(threading.Thread):
    def __init__(self, interval_sec=5.0):
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
                res = subprocess.run(docker_cmd, capture_output=True, text=True, timeout=10)
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
            "clientInfo": {"name": "qa2-load-runner", "version": "2.0.0"}
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

def parse_governed_response(status, body, down_dur_ms):
    """
    Forensic validation of JSON-RPC response body.
    Categorizes outcome into explicit classification:
      - SUCCESSFUL_GOVERNED_EXECUTION
      - RATE_LIMIT_EXCEEDED
      - POLICY_DENIED
      - TOOL_EXECUTION_ERROR
      - INVALID_TIMING_ERROR
      - MALFORMED_JSON_ERROR
      - UNEXPECTED_PAYLOAD_ERROR
      - HTTP_TRANSPORT_ERROR
    """
    if status != 200:
        return "HTTP_TRANSPORT_ERROR", f"HTTP {status}"

    raw = body.decode("utf-8", errors="replace")
    data = parse_mcp_body(raw)
    if not data or ("error" not in data and "result" not in data):
        return "MALFORMED_JSON_ERROR", f"Could not parse MCP JSON-RPC payload: {raw[:150]}"

    if "error" in data:
        err = data["error"]
        msg = str(err.get("message", ""))
        code = err.get("code")
        if "RATE_LIMIT_EXCEEDED" in msg or "rate limit" in msg.lower():
            return "RATE_LIMIT_EXCEEDED", msg
        elif "POLICY_DENIED" in msg or "policy" in msg.lower():
            return "POLICY_DENIED", msg
        else:
            return f"JSON_RPC_ERROR_{code}", msg

    if "result" in data:
        res = data["result"]
        if res.get("isError"):
            return "TOOL_EXECUTION_ERROR", "Tool execution returned isError=true"

        content = res.get("content", [])
        text = ""
        for c in content:
            if c.get("type") == "text":
                text += c.get("text", "")

        if "ORD-BENCH-01" in text or "customerName" in text:
            if down_dur_ms <= 0.0:
                return "INVALID_TIMING_ERROR", "Downstream duration reported as 0.0 ms"
            return "SUCCESSFUL_GOVERNED_EXECUTION", ""
        else:
            return "UNEXPECTED_PAYLOAD_ERROR", f"Payload missing expected content: {text[:100]}"

    return "UNKNOWN_JSON_RPC_FORMAT", "Neither result nor error found in response"

def run_phase(name, is_governed, pool, duration_sec, target_rps, token, session_id, concurrency=20):
    total_requests = int(duration_sec * target_rps)
    interval = 1.0 / target_rps
    results = []
    print(f"\n[{name.upper()}] Starting: duration={duration_sec}s, target_rps={target_rps}, total_requests={total_requests}, concurrency={concurrency}")

    # Pre-warm connection pool to eliminate idle keep-alive socket drops
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

    direct_headers = {
        "Accept": "application/json",
        "Connection": "keep-alive"
    }

    lock = threading.Lock()

    def worker(req_idx, dispatch_target_time):
        corr_id = f"{name}-{req_idx:06d}"
        conn = pool.get()
        t_start = time.perf_counter()
        discard = False
        try:
            if is_governed:
                h = dict(mcp_headers)
                h["X-Request-Correlation-Id"] = corr_id
                conn.request("POST", "/mcp", body=call_payload, headers=h)
            else:
                h = dict(direct_headers)
                h["X-Request-Correlation-Id"] = corr_id
                conn.request("GET", DOWNSTREAM_PATH, headers=h)

            resp = conn.getresponse()
            body = resp.read()
            t_end = time.perf_counter()
            client_lat_ms = (t_end - t_start) * 1000.0

            gw_dur_ms = 0.0
            down_dur_ms = 0.0
            over_ms = 0.0

            if is_governed:
                gw_ns = resp.headers.get("x-gateway-duration-ns")
                down_ns = resp.headers.get("x-downstream-duration-ns")
                over_ns = resp.headers.get("x-gateway-overhead-ns")
                if gw_ns:
                    gw_dur_ms = float(gw_ns) / 1e6
                if down_ns:
                    down_dur_ms = float(down_ns) / 1e6
                if over_ns:
                    over_ms = float(over_ns) / 1e6

                classification, err_detail = parse_governed_response(resp.status, body, down_dur_ms)
            else:
                if resp.status == 200:
                    classification = "SUCCESSFUL_DIRECT_EXECUTION"
                    err_detail = ""
                else:
                    classification = f"HTTP_{resp.status}_ERROR"
                    err_detail = f"HTTP {resp.status}"

            res = {
                "phase": name,
                "request_index": req_idx,
                "timestamp_s": round(t_start - run_start_time, 4),
                "status_code": resp.status,
                "client_latency_ms": round(client_lat_ms, 3),
                "gateway_duration_ms": round(gw_dur_ms, 3),
                "downstream_duration_ms": round(down_dur_ms, 3),
                "overhead_ms": round(over_ms, 3),
                "correlation_id": corr_id,
                "classification": classification,
                "error": err_detail
            }
        except Exception as e:
            t_end = time.perf_counter()
            discard = True
            res = {
                "phase": name,
                "request_index": req_idx,
                "timestamp_s": round(t_start - run_start_time, 4),
                "status_code": 0,
                "client_latency_ms": round((t_end - t_start) * 1000.0, 3),
                "gateway_duration_ms": 0.0,
                "downstream_duration_ms": 0.0,
                "overhead_ms": 0.0,
                "correlation_id": corr_id,
                "classification": "CONNECTION_EXCEPTION",
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

def run_calibration_probe(token, sid):
    """
    Single probe + 200-call burst calibration test.
    Returns True if calibration passes, False otherwise.
    """
    print("\n--- Calibration Gate: Single Probe ---")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": sid
    }
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 999,
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
            gw_ns = resp.headers.get("x-gateway-duration-ns")
            down_ns = resp.headers.get("x-downstream-duration-ns")
            over_ns = resp.headers.get("x-gateway-overhead-ns")
            gw_ms = float(gw_ns) / 1e6 if gw_ns else 0.0
            down_ms = float(down_ns) / 1e6 if down_ns else 0.0
            over_ms = float(over_ns) / 1e6 if over_ns else 0.0
            classification, err = parse_governed_response(resp.status, body, down_ms)
            print(f"Probe Result: status={resp.status}, classification={classification}")
            print(f"Timing Headers: gw_dur={gw_ms:.2f}ms, down_dur={down_ms:.2f}ms, overhead={over_ms:.2f}ms")
            if classification != "SUCCESSFUL_GOVERNED_EXECUTION" or down_ms <= 0.0:
                print(f"[CALIBRATION FAILED] Single probe failed or downstream duration is 0: {err}")
                return False
    except Exception as e:
        print(f"[CALIBRATION FAILED] Single probe exception: {e}")
        return False

    print("\n--- Calibration Gate: 200-Call Burst at 100 RPS (Threshold Verification) ---")
    pool = ConnectionPool(GATEWAY_HOST, GATEWAY_PORT, max_size=25)
    try:
        burst_results, burst_dur, burst_rps = run_phase("calibration_burst", True, pool, 2.0, 100, token, sid, concurrency=20)
        successes = sum(1 for s in burst_results if s["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION")
        rate_limits = sum(1 for s in burst_results if s["classification"] == "RATE_LIMIT_EXCEEDED")
        invalid_timings = sum(1 for s in burst_results if s["classification"] == "INVALID_TIMING_ERROR")
        print(f"Calibration Burst Results (Total: {len(burst_results)}):")
        print(f"  SUCCESSFUL_GOVERNED_EXECUTION: {successes}")
        print(f"  RATE_LIMIT_EXCEEDED:           {rate_limits}")
        print(f"  INVALID_TIMING_ERROR:          {invalid_timings}")
        if successes != 200:
            print(f"[CALIBRATION FAILED] Expected 200 successful executions, got {successes}. Rate limits: {rate_limits}")
            return False
        print("[CALIBRATION PASSED] All 200 burst calls succeeded beyond 60 RPM with valid downstream timings!")
        return True
    finally:
        pool.close_all()

def main():
    parser = argparse.ArgumentParser(description="QA2 Performance Benchmark Load Runner (v2 Validated)")
    parser.add_argument("--mode", choices=["full", "calibrate-only"], default="full")
    parser.add_argument("--target-rps", type=int, default=100)
    parser.add_argument("--warmup-sec", type=int, default=30)
    parser.add_argument("--direct-sec", type=int, default=60)
    parser.add_argument("--governed-sec", type=int, default=60)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--workers", type=int, default=20)
    args = parser.parse_args()

    print("=================================================================")
    print("AgentGate QA2 Performance Benchmark Execution (v2 Validated)")
    print(f"Target RPS: {args.target_rps} | Governed Runs: {args.runs} x {args.governed_sec}s | Baseline: {args.direct_sec}s")
    print("=================================================================")

    # 1. Acquire tokens & initialize sessions
    print("[1/6] Acquiring tokens and setting up connections...")
    token = get_client_token("agent-support-bot")
    admin_token = get_user_token("human-admin")
    sid = init_governed_session(token)
    print(f"MCP Session initialized: {sid}")

    # Mandatory Calibration Gate
    cal_ok = run_calibration_probe(token, sid)
    if not cal_ok:
        print("\nFATAL: Calibration gate failed! Halting benchmark rerun to avoid generating invalid data.")
        sys.exit(1)

    if args.mode == "calibrate-only":
        print("\nCalibration mode complete. Exiting.")
        return

    gw_pool = ConnectionPool(GATEWAY_HOST, GATEWAY_PORT, max_size=args.workers + 5)
    down_pool = ConnectionPool(DOWNSTREAM_HOST, DOWNSTREAM_PORT, max_size=args.workers + 5)

    # 2. Start Resource Monitor
    monitor = ResourceMonitor(interval_sec=5.0)
    monitor.start()

    all_samples = []

    try:
        # Phase 1: Warmup
        monitor.set_phase("warmup")
        warmup_results, _, _ = run_phase("warmup", True, gw_pool, args.warmup_sec, 50, token, sid, concurrency=args.workers)

        # Phase 2: Direct Downstream Baseline
        monitor.set_phase("direct_baseline")
        direct_results, direct_dur, direct_rps = run_phase("direct_baseline", False, down_pool, args.direct_sec, args.target_rps, None, None, concurrency=args.workers)
        all_samples.extend(direct_results)

        # Phase 3-5: Governed Runs 1, 2, 3
        governed_runs_results = []
        for run_idx in range(1, args.runs + 1):
            run_name = f"governed_run{run_idx}"
            monitor.set_phase(run_name)
            run_results, r_dur, r_rps = run_phase(run_name, True, gw_pool, args.governed_sec, args.target_rps, token, sid, concurrency=args.workers)
            governed_runs_results.append((run_name, run_results, r_dur, r_rps))
            all_samples.extend(run_results)
            time.sleep(2.0)

    finally:
        monitor.stop()
        gw_pool.close_all()
        down_pool.close_all()

    # 3. Post-Benchmark Cryptographic Audit Chain Verification
    print("\n[5/6] Verifying cryptographic audit chain integrity...")
    audit_verify_req = urllib.request.Request(f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/api/v1/admin/audit/verify", method="POST")
    audit_verify_req.add_header("Authorization", f"Bearer {admin_token}")
    audit_verify_req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(audit_verify_req, timeout=30) as resp:
        audit_verify_data = json.loads(resp.read().decode("utf-8"))
        print(f"Audit verification result: {audit_verify_data}")

    with open(os.path.join(DATA_DIR, "qa2_v2_audit_verification.json"), "w", encoding="utf-8") as f:
        json.dump(audit_verify_data, f, indent=2)

    # 4. Save CSV Latency Samples (v2)
    print("\n[6/6] Exporting versioned v2 CSV and JSON data files...")
    samples_csv_path = os.path.join(DATA_DIR, "qa2_v2_latency_samples.csv")
    with open(samples_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "phase", "request_index", "timestamp_s", "status_code",
            "client_latency_ms", "gateway_duration_ms", "downstream_duration_ms",
            "overhead_ms", "correlation_id", "classification", "error"
        ])
        writer.writeheader()
        for s in all_samples:
            row = {k: s.get(k, "") for k in writer.fieldnames}
            writer.writerow(row)

    # Save Throughput Timeseries (v2)
    ts_csv_path = os.path.join(DATA_DIR, "qa2_v2_throughput_timeseries.csv")
    with open(ts_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["phase", "second", "actual_rps", "error_count", "p50_overhead_ms", "p95_overhead_ms"])
        for phase_name in ["direct_baseline", "governed_run1", "governed_run2", "governed_run3"]:
            phase_samples = [s for s in all_samples if s["phase"] == phase_name]
            sec_buckets = {}
            for s in phase_samples:
                sec = int(s["timestamp_s"])
                sec_buckets.setdefault(sec, []).append(s)
            for sec, b_samples in sorted(sec_buckets.items()):
                errs = sum(1 for x in b_samples if x["classification"] not in ("SUCCESSFUL_GOVERNED_EXECUTION", "SUCCESSFUL_DIRECT_EXECUTION"))
                overs = [x["overhead_ms"] for x in b_samples if x["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]
                st = calculate_stats(overs)
                writer.writerow([phase_name, sec, len(b_samples), errs, st["p50"], st["p95"]])

    # Save Resource Samples (v2)
    res_csv_path = os.path.join(DATA_DIR, "qa2_v2_resource_samples.csv")
    with open(res_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["phase", "timestamp_s", "container_name", "cpu_percent", "memory_usage_mb"])
        writer.writeheader()
        for r in monitor.samples:
            writer.writerow(r)

    # Compute Aggregate Metrics
    direct_lats = [s["client_latency_ms"] for s in direct_results if s["classification"] == "SUCCESSFUL_DIRECT_EXECUTION"]
    direct_stats = calculate_stats(direct_lats)

    summary = {
        "benchmark_timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "target_rps": args.target_rps,
        "direct_baseline": {
            "total_requests": len(direct_results),
            "actual_duration_s": round(direct_dur, 2),
            "actual_rps": round(direct_rps, 2),
            "error_count": sum(1 for s in direct_results if s["classification"] != "SUCCESSFUL_DIRECT_EXECUTION"),
            "client_latency_ms": direct_stats
        },
        "governed_runs": {},
        "pooled_governed": {}
    }

    pooled_client_lats = []
    pooled_gw_durs = []
    pooled_down_durs = []
    pooled_overheads = []
    pooled_classification_counts = {}
    total_governed_calls = 0

    for r_name, r_samples, r_dur, r_rps in governed_runs_results:
        # Only valid successful governed tool executions are used for the primary metric
        valid_samples = [s for s in r_samples if s["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]
        c_lats = [s["client_latency_ms"] for s in valid_samples]
        g_durs = [s["gateway_duration_ms"] for s in valid_samples]
        d_durs = [s["downstream_duration_ms"] for s in valid_samples]
        o_lats = [s["overhead_ms"] for s in valid_samples]

        class_counts = {}
        for s in r_samples:
            c = s["classification"]
            class_counts[c] = class_counts.get(c, 0) + 1
            pooled_classification_counts[c] = pooled_classification_counts.get(c, 0) + 1

        pooled_client_lats.extend(c_lats)
        pooled_gw_durs.extend(g_durs)
        pooled_down_durs.extend(d_durs)
        pooled_overheads.extend(o_lats)
        total_governed_calls += len(r_samples)

        err_count = sum(cnt for c, cnt in class_counts.items() if c != "SUCCESSFUL_GOVERNED_EXECUTION")

        summary["governed_runs"][r_name] = {
            "total_requests": len(r_samples),
            "successful_requests": len(valid_samples),
            "actual_duration_s": round(r_dur, 2),
            "actual_rps": round(r_rps, 2),
            "error_count": err_count,
            "error_rate_percent": round(err_count / len(r_samples) * 100.0, 3) if r_samples else 0,
            "classification_breakdown": class_counts,
            "client_latency_ms": calculate_stats(c_lats),
            "gateway_duration_ms": calculate_stats(g_durs),
            "downstream_duration_ms": calculate_stats(d_durs),
            "overhead_ms": calculate_stats(o_lats),
            "client_delta_p95_ms": round(calculate_stats(c_lats)["p95"] - direct_stats["p95"], 3) if c_lats else 0.0
        }

    pooled_c_stats = calculate_stats(pooled_client_lats)
    pooled_o_stats = calculate_stats(pooled_overheads)
    delta_client_p95 = round(pooled_c_stats["p95"] - direct_stats["p95"], 3) if pooled_client_lats else 0.0

    qa2_overhead_p95 = pooled_o_stats["p95"]
    qa2_passed = qa2_overhead_p95 <= 30.0 and pooled_o_stats["count"] == total_governed_calls

    pooled_errors = sum(cnt for c, cnt in pooled_classification_counts.items() if c != "SUCCESSFUL_GOVERNED_EXECUTION")

    summary["pooled_governed"] = {
        "total_requests": total_governed_calls,
        "successful_requests": len(pooled_overheads),
        "error_count": pooled_errors,
        "error_rate_percent": round(pooled_errors / total_governed_calls * 100.0, 3) if total_governed_calls else 0,
        "classification_breakdown": pooled_classification_counts,
        "client_latency_ms": pooled_c_stats,
        "gateway_duration_ms": calculate_stats(pooled_gw_durs),
        "downstream_duration_ms": calculate_stats(pooled_down_durs),
        "overhead_ms": pooled_o_stats,
        "client_delta_p95_ms": delta_client_p95
    }

    summary["qa2_evaluation"] = {
        "authoritative_requirement": "The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits.",
        "primary_metric": "Incremental Gateway Overhead p95 (overhead_ms = gateway_duration_ms - downstream_duration_ms) on successful executions",
        "measured_overhead_p95_ms": qa2_overhead_p95,
        "overhead_threshold_ms": 30.0,
        "secondary_metric": "Client End-to-End Latency Delta p95 (p95(governed) - p95(direct))",
        "measured_client_delta_p95_ms": delta_client_p95,
        "valid_governed_executions": len(pooled_overheads),
        "total_governed_requests": total_governed_calls,
        "verdict": "PASS" if qa2_passed else "FAIL",
        "audit_chain_verified": audit_verify_data.get("valid", False),
        "total_audit_records_verified": audit_verify_data.get("recordsChecked", 0)
    }

    summary_json_path = os.path.join(DATA_DIR, "qa2_v2_benchmark_summary.json")
    with open(summary_json_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    # Save Config (v2)
    config_data = {
        "benchmark_version": "v2",
        "target_rps": args.target_rps,
        "warmup_sec": args.warmup_sec,
        "direct_sec": args.direct_sec,
        "governed_sec": args.governed_sec,
        "runs": args.runs,
        "workers": args.workers,
        "endpoints": {
            "gateway_mcp": GATEWAY_MCP_URL,
            "downstream_direct": f"http://{DOWNSTREAM_HOST}:{DOWNSTREAM_PORT}{DOWNSTREAM_PATH}",
            "keycloak_token": "http://localhost:8180/realms/agentgate/protocol/openid-connect/token"
        },
        "environment": {
            "os": "Windows 11 Pro",
            "jvm": "Eclipse Temurin Java 21",
            "docker_engine": "Docker Desktop 28.x (WSL2 backend)",
            "gateway_container": "agentgate-benchmark-gateway (512MB RAM)",
            "postgres_container": "agentgate-benchmark-postgres (256MB RAM)",
            "redis_container": "agentgate-benchmark-redis (64MB RAM)",
            "demo_tools_container": "agentgate-benchmark-demo-tools (192MB RAM)",
            "spring_profiles": "dev,benchmark",
            "agent_support_bot_rpm_override": 12000,
            "benchmark_read_rpm_override": 12000
        }
    }
    with open(os.path.join(DATA_DIR, "qa2_v2_benchmark_config.json"), "w", encoding="utf-8") as f:
        json.dump(config_data, f, indent=2)

    print("\n=================================================================")
    print("BENCHMARK EXECUTION COMPLETE (v2 Validated)")
    print(f"Direct Baseline Client Latency p95: {direct_stats['p95']:.2f} ms")
    print(f"Governed Client Latency p95:        {pooled_c_stats['p95']:.2f} ms")
    print(f"Client-Side Delta p95:             {delta_client_p95:.2f} ms")
    print(f"Primary Metric (Overhead p95):      {qa2_overhead_p95:.2f} ms (Threshold <= 30.0 ms)")
    print(f"Classification Breakdown:          {pooled_classification_counts}")
    print(f"VERDICT:                           {summary['qa2_evaluation']['verdict']}")
    print(f"Audit Chain Integrity:             {'VERIFIED' if audit_verify_data.get('valid') else 'FAILED'} ({audit_verify_data.get('recordsChecked')} records)")
    print("=================================================================")

if __name__ == "__main__":
    main()
