#!/usr/bin/env python3
"""
AgentGate — M04 Task 16F-E: Final QA2 Acceptance Benchmark Runner
Implements the reviewer-approved acceptance protocol:
  - Phase 0: Calibration Gate (1 probe)
  - Phase 1 (Warm-up, excluded from acceptance):
      10 RPS (15s = 150), 30 RPS (15s = 450), 60 RPS (15s = 900), 100 RPS Stabilization (60s = 6,000)
  - Phase 2-4 (Acceptance Windows, pooled = 18,000 calls):
      Window 1: 60s @ 100 RPS (6,000 calls)
      Window 2: 60s @ 100 RPS (6,000 calls)
      Window 3: 60s @ 100 RPS (6,000 calls)

Client: SingleBufferHTTPConnection (HTTP/1.1 Persistent Keep-Alive, TCP_NODELAY=1, Consolidated Framing)
Monitors: PostgreSQL row locks, HikariCP pool metrics, Docker container CPU/Memory, SHA-256 Audit Chain
"""

import concurrent.futures
import csv
import http.client
import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT_DIR, "scripts"))
from verify_qa1_security import get_client_token, get_user_token

DATA_DIR = os.path.join(ROOT_DIR, "scripts", "benchmarks", "data")
os.makedirs(DATA_DIR, exist_ok=True)

GATEWAY_HOST = "localhost"
GATEWAY_PORT = 8081
GATEWAY_MCP_URL = f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/mcp"
POSTGRES_CONTAINER = "agentgate-benchmark-postgres"
DOCKER_BIN = "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"


class SingleBufferHTTPConnection(http.client.HTTPConnection):
    """Combines HTTP/1.1 request line, headers, and body into a single socket write (sendall)."""
    def _send_output(self, message_body=None, encode_chunked=False):
        self._buffer.extend((b"", b""))
        msg = b"\r\n".join(self._buffer)
        del self._buffer[:]
        if message_body is not None:
            if hasattr(message_body, 'read'):
                body_bytes = message_body.read()
            elif isinstance(message_body, str):
                body_bytes = message_body.encode("latin-1")
            elif isinstance(message_body, (bytes, bytearray, memoryview)):
                body_bytes = bytes(message_body)
            else:
                body_bytes = b"".join(message_body)
            self.send(msg + body_bytes)
        else:
            self.send(msg)


class ConnectionPool:
    def __init__(self, host, port, max_size=30, timeout=15):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.max_size = max_size
        self.pool = queue.Queue(maxsize=max_size)
        for _ in range(max_size):
            self.pool.put(self._create_conn())

    def _create_conn(self):
        return SingleBufferHTTPConnection(self.host, self.port, timeout=self.timeout)

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
    def __init__(self, interval_sec=0.5):
        super().__init__(daemon=True)
        self.interval_sec = interval_sec
        self.running = True
        self.current_phase = "init"
        self.samples = []

    def set_phase(self, phase):
        self.current_phase = phase

    def run(self):
        psql_cmd = [
            DOCKER_BIN, "exec", "-i", POSTGRES_CONTAINER,
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
                        self.samples.append({
                            "phase": self.current_phase,
                            "timestamp_s": round(t0, 3),
                            "waiting_locks_total": int(lines[0]),
                            "waiting_chain_locks": int(lines[1]),
                            "total_chain_locks": int(lines[2])
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
        self.current_phase = "init"
        self.container_samples = []
        self.hikari_samples = []

    def set_phase(self, phase):
        self.current_phase = phase

    def _fetch_metric(self, metric_name):
        try:
            with urllib.request.urlopen(f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/actuator/metrics/{metric_name}", timeout=2) as r:
                data = json.loads(r.read().decode("utf-8"))
                measurements = data.get("measurements", [])
                if measurements:
                    return measurements[0].get("value", 0.0)
        except Exception:
            pass
        return None

    def run(self):
        docker_cmd = [DOCKER_BIN, "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}"]
        while self.running:
            t0 = time.time()
            try:
                res = subprocess.run(docker_cmd, capture_output=True, text=True, timeout=8)
                if res.returncode == 0:
                    for line in res.stdout.strip().splitlines():
                        if "benchmark" in line:
                            parts = line.split(",")
                            if len(parts) >= 3:
                                name = parts[0].strip()
                                cpu_str = parts[1].strip().replace("%", "")
                                mem_str = parts[2].strip().split("/")[0].strip()
                                mem_mb = 0.0
                                if "MiB" in mem_str or "MB" in mem_str:
                                    mem_mb = float(mem_str.replace("MiB", "").replace("MB", "").strip())
                                elif "GiB" in mem_str or "GB" in mem_str:
                                    mem_mb = float(mem_str.replace("GiB", "").replace("GB", "").strip()) * 1024.0
                                self.container_samples.append({
                                    "phase": self.current_phase,
                                    "timestamp_s": round(t0, 3),
                                    "container_name": name,
                                    "cpu_percent": float(cpu_str) if cpu_str else 0.0,
                                    "memory_usage_mb": mem_mb
                                })
            except Exception:
                pass

            active = self._fetch_metric("hikaricp.connections.active")
            pending = self._fetch_metric("hikaricp.connections.pending")
            idle = self._fetch_metric("hikaricp.connections.idle")
            if active is not None:
                self.hikari_samples.append({
                    "phase": self.current_phase,
                    "timestamp_s": round(t0, 3),
                    "active": active,
                    "pending": pending,
                    "idle": idle
                })

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
            "clientInfo": {"name": "qa2-final-acceptance", "version": "1.0.0"}
        }
    }
    data = json.dumps(init_payload).encode("utf-8")
    req = urllib.request.Request(GATEWAY_MCP_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")


def verify_audit_chain(admin_token):
    req = urllib.request.Request(f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/api/v1/admin/audit/verify", method="POST")
    req.add_header("Authorization", f"Bearer {admin_token}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


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


def calculate_stats(values):
    if not values:
        return {"count": 0, "mean": 0.0, "std": 0.0, "min": 0.0, "p50": 0.0, "p90": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0}
    s = sorted(values)
    n = len(s)
    mean = sum(s) / n
    variance = sum((x - mean) ** 2 for x in s) / n if n > 1 else 0.0
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
    conn = SingleBufferHTTPConnection(GATEWAY_HOST, GATEWAY_PORT, timeout=10)
    conn.connect()
    tcp_nodelay = conn.sock.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY)

    call_payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 99,
        "method": "tools/call",
        "params": {"name": "benchmark_read", "arguments": {"id": "ORD-BENCH-01"}}
    }).encode("utf-8")

    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": sid or "",
        "X-Request-Correlation-Id": "qa2-final-calibration-probe"
    }

    t0 = time.perf_counter()
    conn.request("POST", "/mcp", body=call_payload, headers=headers)
    resp = conn.getresponse()
    body = resp.read().decode("utf-8", errors="replace")
    t1 = time.perf_counter()
    conn.close()

    def hdr_ms(name):
        v = resp.headers.get(name)
        return round(float(v) / 1e6, 3) if v else None

    gw_ms = hdr_ms("X-Gateway-Duration-Ns")
    down_ms = hdr_ms("X-Downstream-Duration-Ns")
    overhead_ms = round(gw_ms - down_ms, 3) if (gw_ms is not None and down_ms is not None) else None

    data = parse_mcp_body(body)
    valid = (
        resp.status == 200
        and "error" not in data
        and not data.get("result", {}).get("isError", False)
        and "ORD-BENCH-01" in body
        and gw_ms is not None
        and down_ms is not None
        and down_ms > 0.0
    )

    return {
        "valid": valid,
        "status": resp.status,
        "tcp_nodelay": tcp_nodelay,
        "client_latency_ms": round((t1 - t0) * 1000.0, 3),
        "gateway_duration_ms": gw_ms,
        "downstream_duration_ms": down_ms,
        "overhead_ms": overhead_ms,
        "sec_ingress_ms": hdr_ms("X-Profile-Security-Ingress-Ns"),
        "ingress_mcp_parse_ms": hdr_ms("X-Profile-Ingress-McpParse-Ns"),
        "audit_total_ms": hdr_ms("X-Profile-Audit-Total-Ns"),
        "audit_lock_ms": hdr_ms("X-Profile-Audit-Lock-Ns")
    }


def run_phase(phase_name, pool, duration_sec, target_rps, token, session_id, concurrency=25):
    planned_requests = int(round(duration_sec * target_rps))
    interval = 1.0 / target_rps
    results = []
    print(f"\n[{phase_name.upper()}] Planned: duration={duration_sec}s, target_rps={target_rps}, calls={planned_requests}, concurrency={concurrency}")

    pool.refresh_all()

    call_payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 100,
        "method": "tools/call",
        "params": {"name": "benchmark_read", "arguments": {"id": "ORD-BENCH-01"}}
    }).encode("utf-8")

    mcp_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": session_id or ""
    }

    lock = threading.Lock()
    dispatched_count = 0

    def worker(req_idx, scheduled_time):
        req_id = f"{phase_name}-{req_idx:06d}"
        dispatch_time = time.perf_counter()
        dispatch_lag_ms = round((dispatch_time - scheduled_time) * 1000.0, 3)

        conn = pool.get()
        discard = False
        status_code = 0
        mcp_status = "UNKNOWN"
        downstream_valid = False
        classification = "UNRESOLVED"
        client_lat_ms = 0.0
        gw_dur_ms = 0.0
        down_dur_ms = 0.0
        overhead_ms = 0.0
        sec_ingress_ms = 0.0
        ingress_auth_ms = 0.0
        ingress_routing_ms = 0.0
        ingress_mcp_parse_ms = 0.0
        ingress_callback_prep_ms = 0.0
        perm_ms = 0.0
        rate_ms = 0.0
        schema_ms = 0.0
        policy_ms = 0.0
        circuit_ms = 0.0
        audit_total_ms = 0.0
        audit_lock_ms = 0.0
        audit_hash_ms = 0.0
        audit_write_ms = 0.0
        audit_commit_ms = 0.0
        pii_ms = 0.0
        serial_ms = 0.0

        try:
            h = dict(mcp_headers)
            h["X-Request-Correlation-Id"] = req_id
            t_req_start = time.perf_counter()
            conn.request("POST", "/mcp", body=call_payload, headers=h)
            resp = conn.getresponse()
            body = resp.read()
            t_req_end = time.perf_counter()

            client_lat_ms = round((t_req_end - t_req_start) * 1000.0, 3)
            status_code = resp.status

            def parse_hdr(hn):
                val = resp.headers.get(hn)
                return round(float(val) / 1e6, 3) if val else 0.0

            gw_dur_ms = parse_hdr("X-Gateway-Duration-Ns")
            down_dur_ms = parse_hdr("X-Downstream-Duration-Ns")
            overhead_ms = round(max(0.0, gw_dur_ms - down_dur_ms), 3)

            sec_ingress_ms = parse_hdr("X-Profile-Security-Ingress-Ns")
            ingress_auth_ms = parse_hdr("X-Profile-Ingress-Auth-Ns")
            ingress_routing_ms = parse_hdr("X-Profile-Ingress-Routing-Ns")
            ingress_mcp_parse_ms = parse_hdr("X-Profile-Ingress-McpParse-Ns")
            ingress_callback_prep_ms = parse_hdr("X-Profile-Ingress-CallbackPrep-Ns")
            perm_ms = parse_hdr("X-Profile-Permission-Ns")
            rate_ms = parse_hdr("X-Profile-RateLimit-Ns")
            schema_ms = parse_hdr("X-Profile-Schema-Ns")
            policy_ms = parse_hdr("X-Profile-Policy-Ns")
            circuit_ms = parse_hdr("X-Profile-Circuit-Ns")
            audit_total_ms = parse_hdr("X-Profile-Audit-Total-Ns")
            audit_lock_ms = parse_hdr("X-Profile-Audit-Lock-Ns")
            audit_hash_ms = parse_hdr("X-Profile-Audit-Hash-Ns")
            audit_write_ms = parse_hdr("X-Profile-Audit-Write-Ns")
            audit_commit_ms = parse_hdr("X-Profile-Audit-Commit-Ns")
            pii_ms = parse_hdr("X-Profile-Pii-Ns")
            serial_ms = parse_hdr("X-Profile-Serialization-Ns")

            if status_code != 200:
                classification = f"HTTP_{status_code}"
                mcp_status = f"HTTP_ERROR_{status_code}"
            else:
                raw = body.decode("utf-8", errors="replace")
                data = parse_mcp_body(raw)
                if "error" in data:
                    mcp_status = "JSON_RPC_ERROR"
                    classification = f"JSON_RPC_ERROR"
                elif data.get("result", {}).get("isError") is True:
                    mcp_status = "TOOL_ERROR"
                    classification = "TOOL_EXECUTION_ERROR"
                elif "ORD-BENCH-01" not in raw:
                    mcp_status = "CONTENT_MISMATCH"
                    classification = "PAYLOAD_CONTENT_MISMATCH"
                elif down_dur_ms <= 0.0 or gw_dur_ms <= 0.0:
                    mcp_status = "MISSING_TIMING"
                    classification = "INVALID_TIMING_ERROR"
                else:
                    mcp_status = "OK"
                    downstream_valid = True
                    classification = "SUCCESSFUL_GOVERNED_EXECUTION"

        except socket.timeout:
            t_req_end = time.perf_counter()
            client_lat_ms = round((t_req_end - dispatch_time) * 1000.0, 3)
            classification = "TIMEOUT"
            mcp_status = "SOCKET_TIMEOUT"
            discard = True
        except Exception as exc:
            t_req_end = time.perf_counter()
            client_lat_ms = round((t_req_end - dispatch_time) * 1000.0, 3)
            classification = f"CLIENT_EXCEPTION"
            mcp_status = str(type(exc).__name__)
            discard = True
        finally:
            pool.put(conn, discard=discard)

        res = {
            "request_id": req_id,
            "assigned_window": phase_name,
            "request_index": req_idx,
            "scheduled_offset_s": round(scheduled_time - phase_start_time, 4),
            "dispatch_offset_s": round(dispatch_time - phase_start_time, 4),
            "completion_offset_s": round(t_req_end - phase_start_time, 4),
            "dispatch_lag_ms": dispatch_lag_ms,
            "status_code": status_code,
            "mcp_status": mcp_status,
            "downstream_valid": downstream_valid,
            "classification": classification,
            "client_latency_ms": client_lat_ms,
            "gateway_duration_ms": gw_dur_ms,
            "downstream_duration_ms": down_dur_ms,
            "overhead_ms": overhead_ms,
            "sec_ingress_ms": sec_ingress_ms,
            "ingress_auth_ms": ingress_auth_ms,
            "ingress_routing_ms": ingress_routing_ms,
            "ingress_mcp_parse_ms": ingress_mcp_parse_ms,
            "ingress_callback_prep_ms": ingress_callback_prep_ms,
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
            "serialization_ms": serial_ms
        }
        with lock:
            results.append(res)

    phase_start_time = time.perf_counter()
    futures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        for i in range(planned_requests):
            target_time = phase_start_time + i * interval
            now = time.perf_counter()
            if target_time > now:
                time.sleep(target_time - now)
            futures.append(executor.submit(worker, i, target_time))
            dispatched_count += 1
            if (i + 1) % 1500 == 0 or (i + 1) == planned_requests:
                print(f"  [{phase_name}] Dispatched {i + 1}/{planned_requests}...")
        dispatch_end_time = time.perf_counter()
        concurrent.futures.wait(futures)
    drain_end_time = time.perf_counter()

    dispatch_duration_s = dispatch_end_time - phase_start_time
    drain_duration_s = drain_end_time - phase_start_time
    successful = [r for r in results if r["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]
    failed = [r for r in results if r["classification"] not in ("SUCCESSFUL_GOVERNED_EXECUTION", "TIMEOUT", "UNRESOLVED")]
    timed_out = [r for r in results if r["classification"] == "TIMEOUT"]
    unresolved = planned_requests - len(results)

    achieved_rps = len(successful) / drain_duration_s if drain_duration_s > 0 else 0.0

    accounting = {
        "phase": phase_name,
        "target_offered_rps": target_rps,
        "client_concurrency": concurrency,
        "planned_requests": planned_requests,
        "actual_scheduled_requests": planned_requests,
        "actual_dispatched_requests": dispatched_count,
        "completed_requests": len(results),
        "successful_executions": len(successful),
        "failed_executions": len(failed),
        "timed_out_executions": len(timed_out),
        "unresolved_executions": unresolved,
        "dispatch_duration_s": round(dispatch_duration_s, 3),
        "drain_duration_s": round(drain_duration_s, 3),
        "achieved_successful_rps": round(achieved_rps, 2),
        "dispatch_lag_ms": calculate_stats([r["dispatch_lag_ms"] for r in results])
    }

    print(f"[{phase_name.upper()}] Completed: {len(successful)}/{planned_requests} valid in {drain_duration_s:.2f}s "
          f"(achieved_rps={achieved_rps:.2f}, lag_p95={accounting['dispatch_lag_ms']['p95']:.2f}ms)")

    return results, accounting


def summarize_phase(samples, accounting):
    valid = [s for s in samples if s["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]

    def st(key):
        return calculate_stats([s[key] for s in valid])

    return {
        "accounting": accounting,
        "client_latency_ms": st("client_latency_ms"),
        "gateway_duration_ms": st("gateway_duration_ms"),
        "downstream_duration_ms": st("downstream_duration_ms"),
        "overhead_ms": st("overhead_ms"),
        "components_ms": {
            "security_ingress": st("sec_ingress_ms"),
            "ingress_auth": st("ingress_auth_ms"),
            "ingress_routing": st("ingress_routing_ms"),
            "ingress_mcp_parse": st("ingress_mcp_parse_ms"),
            "ingress_callback_prep": st("ingress_callback_prep_ms"),
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


def export_csv(path, rows):
    if not rows:
        return
    fieldnames = list(rows[0].keys())
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main():
    print("=========================================================================")
    print("AgentGate — M04 Task 16F-E: Final QA2 Acceptance Benchmark Runner")
    print("Client: SingleBufferHTTPConnection (HTTP/1.1 Keep-Alive, TCP_NODELAY=1)")
    print("=========================================================================")

    token = get_client_token("agent-support-bot")
    admin_token = get_user_token("human-admin")

    # Pre-benchmark audit verification
    initial_audit = verify_audit_chain(admin_token)
    print(f"Initial Audit Verification: {initial_audit}")
    if not initial_audit.get("valid"):
        print("FATAL: Initial audit chain invalid! Aborting.")
        sys.exit(1)

    sid = init_governed_session(token)
    print(f"MCP Session initialized: {sid}")

    # Phase 0: Calibration Gate
    cal = run_calibration_probe(token, sid)
    print(f"Calibration Gate: {cal}")
    if not cal["valid"]:
        print("FATAL: Calibration probe failed validation! Aborting.")
        sys.exit(1)

    pool = ConnectionPool(GATEWAY_HOST, GATEWAY_PORT, max_size=30)
    lock_monitor = PostgresLockMonitor(interval_sec=0.5)
    res_monitor = ResourceMonitor(interval_sec=2.0)

    lock_monitor.start()
    res_monitor.start()

    warmup_samples = []
    warmup_accounting = {}
    acceptance_samples = []
    acceptance_accounting = {}

    try:
        # Phase 1: Warm-up & Stabilization (Excluded from Acceptance)
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

        # Phase 2-4: Acceptance Windows (3 x 60s @ 100 RPS = 18,000 calls)
        window_specs = [
            ("window_1", 60.0, 100, 25),
            ("window_2", 60.0, 100, 25),
            ("window_3", 60.0, 100, 25)
        ]
        for pname, dur, rps, conc in window_specs:
            lock_monitor.set_phase(pname)
            res_monitor.set_phase(pname)
            samples, acct = run_phase(pname, pool, dur, rps, token, sid, concurrency=conc)
            acceptance_samples.extend(samples)
            acceptance_accounting[pname] = (samples, acct)
            time.sleep(2.0)

    finally:
        lock_monitor.stop()
        res_monitor.stop()
        pool.close_all()

    # Post-benchmark audit verification
    final_audit = verify_audit_chain(admin_token)
    print(f"\nFinal Audit Verification: {final_audit}")

    # Export raw datasets
    export_csv(os.path.join(DATA_DIR, "qa2_final_warmup_samples.csv"), warmup_samples)
    export_csv(os.path.join(DATA_DIR, "qa2_final_acceptance_samples.csv"), acceptance_samples)

    with open(os.path.join(DATA_DIR, "qa2_final_lock_stats.json"), "w", encoding="utf-8") as f:
        json.dump(lock_monitor.samples, f, indent=2)

    with open(os.path.join(DATA_DIR, "qa2_final_resource_stats.json"), "w", encoding="utf-8") as f:
        json.dump({
            "container_stats": res_monitor.container_samples,
            "hikari_stats": res_monitor.hikari_samples
        }, f, indent=2)

    # Summarize warm-up
    warmup_summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "phases": {p: summarize_phase(s, a) for p, (s, a) in warmup_accounting.items()}
    }
    with open(os.path.join(DATA_DIR, "qa2_final_warmup_summary.json"), "w", encoding="utf-8") as f:
        json.dump(warmup_summary, f, indent=2)

    # Summarize acceptance windows + pooled
    pooled_valid = [s for s in acceptance_samples if s["classification"] == "SUCCESSFUL_GOVERNED_EXECUTION"]
    total_drain_s = sum(a["drain_duration_s"] for (_, a) in acceptance_accounting.values())
    pooled_acct = {
        "phase": "pooled_acceptance",
        "target_offered_rps": 100,
        "planned_requests": sum(a["planned_requests"] for (_, a) in acceptance_accounting.values()),
        "actual_scheduled_requests": sum(a["actual_scheduled_requests"] for (_, a) in acceptance_accounting.values()),
        "actual_dispatched_requests": sum(a["actual_dispatched_requests"] for (_, a) in acceptance_accounting.values()),
        "completed_requests": len(acceptance_samples),
        "successful_executions": len(pooled_valid),
        "failed_executions": sum(a["failed_executions"] for (_, a) in acceptance_accounting.values()),
        "timed_out_executions": sum(a["timed_out_executions"] for (_, a) in acceptance_accounting.values()),
        "unresolved_executions": sum(a["unresolved_executions"] for (_, a) in acceptance_accounting.values()),
        "total_drain_duration_s": round(total_drain_s, 3),
        "achieved_successful_rps": round(len(pooled_valid) / total_drain_s, 2) if total_drain_s > 0 else 0.0,
        "dispatch_lag_ms": calculate_stats([r["dispatch_lag_ms"] for r in acceptance_samples])
    }

    acceptance_summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "client_implementation": "SingleBufferHTTPConnection (HTTP/1.1 Keep-Alive, TCP_NODELAY=1, Consolidated Wire Framing)",
        "calibration_probe": cal,
        "initial_audit_verification": initial_audit,
        "final_audit_verification": final_audit,
        "windows": {p: summarize_phase(s, a) for p, (s, a) in acceptance_accounting.items()},
        "pooled": summarize_phase(acceptance_samples, pooled_acct)
    }

    with open(os.path.join(DATA_DIR, "qa2_final_acceptance_summary.json"), "w", encoding="utf-8") as f:
        json.dump(acceptance_summary, f, indent=2)

    print("\n=========================================================================================================")
    print(f"{'Phase':<18} | {'Success':<12} | {'Achieved RPS':<13} | {'Overhead p50':<13} | {'Overhead p95':<13} | {'Audit p95':<10} | {'MCP p95':<9}")
    print("---------------------------------------------------------------------------------------------------------")
    for p in ["window_1", "window_2", "window_3"]:
        w = acceptance_summary["windows"][p]
        ac = w["accounting"]
        ov = w["overhead_ms"]
        au = w["components_ms"]["audit_total"]
        mc = w["components_ms"]["ingress_mcp_parse"]
        print(f"{p:<18} | {ac['successful_executions']}/{ac['planned_requests']:<5} | {ac['achieved_successful_rps']:<13.2f} | {ov['p50']:<13.2f} | {ov['p95']:<13.2f} | {au['p95']:<10.2f} | {mc['p95']:<9.2f}")
    po = acceptance_summary["pooled"]
    pac = po["accounting"]
    pov = po["overhead_ms"]
    pau = po["components_ms"]["audit_total"]
    pmc = po["components_ms"]["ingress_mcp_parse"]
    print("---------------------------------------------------------------------------------------------------------")
    print(f"{'POOLED (18k)':<18} | {pac['successful_executions']}/{pac['planned_requests']:<5} | {pac['achieved_successful_rps']:<13.2f} | {pov['p50']:<13.2f} | {pov['p95']:<13.2f} | {pau['p95']:<10.2f} | {pmc['p95']:<9.2f}")
    print("=========================================================================================================")


if __name__ == "__main__":
    main()
