#!/usr/bin/env python3
"""
AgentGate — Diagnostic Probe for Client Transport Variants (Task 16F-D)
Compares:
  Variant A: Standard http.client.HTTPConnection (split send: headers then body)
  Variant C: SingleBufferHTTPConnection (single sendall: headers + body in 1 buffer)
"""

import http.client
import json
import os
import sys
import time
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT_DIR, "scripts"))
from verify_qa1_security import get_client_token

GATEWAY_HOST = "localhost"
GATEWAY_PORT = 8081
GATEWAY_MCP_URL = f"http://{GATEWAY_HOST}:{GATEWAY_PORT}/mcp"

class SingleBufferHTTPConnection(http.client.HTTPConnection):
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

def init_session(token):
    init_payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "variant-tester", "version": "1.0.0"}
        }
    }
    data = json.dumps(init_payload).encode("utf-8")
    req = urllib.request.Request(GATEWAY_MCP_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")

def run_probe(conn_cls, token, session_id, count=15, label="Variant"):
    call_payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 100,
        "method": "tools/call",
        "params": {
            "name": "benchmark_read",
            "arguments": {"id": "ORD-BENCH-01"}
        }
    }).encode("utf-8")

    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Mcp-Session-Id": session_id or ""
    }

    results = []
    conn = conn_cls(GATEWAY_HOST, GATEWAY_PORT, timeout=10)
    conn.connect()

    for i in range(count):
        h = dict(headers)
        h["X-Request-Correlation-Id"] = f"probe-{label}-{i:03d}"
        t0 = time.perf_counter()
        conn.request("POST", "/mcp", body=call_payload, headers=h)
        resp = conn.getresponse()
        body = resp.read()
        t1 = time.perf_counter()

        def parse_header_ms(hn):
            v = resp.headers.get(hn)
            return round(float(v) / 1e6, 3) if v else 0.0

        gw_dur = parse_header_ms("X-Gateway-Duration-Ns")
        down_dur = parse_header_ms("X-Downstream-Duration-Ns")
        overhead = parse_header_ms("X-Gateway-Overhead-Ns")
        sec_ingress = parse_header_ms("X-Profile-Security-Ingress-Ns")
        auth = parse_header_ms("X-Profile-Ingress-Auth-Ns")
        route = parse_header_ms("X-Profile-Ingress-Routing-Ns")
        mcp_parse = parse_header_ms("X-Profile-Ingress-McpParse-Ns")
        prep = parse_header_ms("X-Profile-Ingress-CallbackPrep-Ns")
        client_lat = round((t1 - t0) * 1000.0, 3)

        results.append({
            "client_lat": client_lat,
            "overhead": overhead,
            "sec_ingress": sec_ingress,
            "auth": auth,
            "route": route,
            "mcp_parse": mcp_parse,
            "prep": prep,
            "status": resp.status
        })
        time.sleep(0.05)

    conn.close()

    def stats(key):
        vals = [r[key] for r in results]
        s = sorted(vals)
        n = len(s)
        p50 = s[int(n * 0.50)]
        p95 = s[min(int(n * 0.95), n - 1)]
        mean = sum(s) / n
        return f"p50={p50:.2f}ms, p95={p95:.2f}ms, mean={mean:.2f}ms"

    print(f"\n--- Results for {label} ({count} calls) ---")
    print(f"  Client Latency:       {stats('client_lat')}")
    print(f"  Gateway Overhead:     {stats('overhead')}")
    print(f"  Security Ingress:     {stats('sec_ingress')}")
    print(f"  - Ingress Auth:       {stats('auth')}")
    print(f"  - Ingress Routing:    {stats('route')}")
    print(f"  - MCP Parse (T2->T3): {stats('mcp_parse')}")
    print(f"  - Callback Prep:      {stats('prep')}")

def main():
    token = get_client_token("agent-support-bot")
    sid = init_session(token)
    print(f"Initialized MCP Session: {sid}")

    # Warmup
    run_probe(http.client.HTTPConnection, token, sid, count=5, label="Warmup")

    # Variant A (Standard split-send)
    run_probe(http.client.HTTPConnection, token, sid, count=25, label="Variant A (Standard Split-Send)")

    # Variant C (Single buffer sendall)
    run_probe(SingleBufferHTTPConnection, token, sid, count=25, label="Variant C (Single-Buffer Sendall)")

if __name__ == "__main__":
    main()
