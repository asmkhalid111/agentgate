#!/usr/bin/env python3
"""
AgentGate — Real PostgreSQL Live Verification & Tamper Detection (FR7)
Connects directly to running PostgreSQL container to verify:
  1. Flyway V7 migration status and schema constraints.
  2. Live audit trail timestamp round-trip hash concordance.
  3. Isolated disposable tampering detection (modification, middle gap, tail deletion).
"""

import hashlib
import json
import subprocess
import copy

def run_psql_json(query):
    sql = f"SELECT json_agg(t) FROM ({query}) t;"
    cmd = [
        "docker", "exec", "agentgate-postgres",
        "psql", "-U", "agentgate", "-d", "agentgate_db",
        "-t", "-A", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    out = res.stdout.strip()
    if not out:
        return []
    return json.loads(out)

def compute_record_hash(r):
    # Matches AuditHashEngine.java buildCanonicalJson: sorted TreeMap with all 16 keys
    canonical_dict = {
        "actor_id": r.get("actor_id"),
        "agent_id": r.get("agent_id"),
        "approval_id": r.get("approval_id"),
        "decision": r.get("decision"),
        "error_message": r.get("error_message"),
        "event_timestamp": r.get("event_timestamp"),
        "event_type": r.get("event_type"),
        "latency_ms": r.get("latency_ms", 0),
        "outcome": r.get("outcome"),
        "policy_rule_id": r.get("policy_rule_id"),
        "policy_rule_name": r.get("policy_rule_name"),
        "prev_record_hash": r.get("prev_record_hash"),
        "redacted_arguments_json": r.get("redacted_arguments_json") if r.get("redacted_arguments_json") is not None else "{}",
        "sequence_number": r.get("sequence_number"),
        "tool_name": r.get("tool_name"),
        "user_id": r.get("user_id")
    }
    canonical_json = json.dumps(canonical_dict, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(canonical_json.encode("utf-8")).hexdigest()

def verify_chain(records, tip_seq, tip_hash):
    genesis = "0000000000000000000000000000000000000000000000000000000000000000"
    if not records:
        if tip_seq == 0 and tip_hash == genesis:
            return True, "Valid empty chain", None
        return False, f"Empty log but tip sequence is {tip_seq}", 0

    last_record = records[-1]
    if tip_seq != last_record["sequence_number"]:
        return False, f"Tip sequence mismatch: state={tip_seq}, log={last_record['sequence_number']}", last_record["sequence_number"]

    if tip_hash != last_record["record_hash"]:
        return False, f"Tip hash mismatch: state={tip_hash}, log={last_record['record_hash']}", last_record["sequence_number"]

    for i, r in enumerate(records):
        expected_seq = i + 1
        if r["sequence_number"] != expected_seq:
            return False, f"Sequence gap: expected {expected_seq}, found {r['sequence_number']}", r["sequence_number"]

        expected_prev = genesis if i == 0 else records[i - 1]["record_hash"]
        if r["prev_record_hash"] != expected_prev:
            return False, f"Broken linkage at seq {r['sequence_number']}: expected prev {expected_prev}, got {r['prev_record_hash']}", r["sequence_number"]

        computed = compute_record_hash(r)
        if r["record_hash"] != computed:
            return False, f"Tampered record at seq {r['sequence_number']}: computed {computed}, stored {r['record_hash']}", r["sequence_number"]

    return True, f"Integrity verified across {len(records)} records", None

def main():
    print("=" * 70)
    print("AgentGate — Real PostgreSQL Live Verification (FR7)")
    print("=" * 70)

    # 1. Flyway V7 Verification
    print("\n[1/3] Verifying Flyway migration V7 in PostgreSQL...")
    v7_res = run_psql_json("SELECT version, description, success FROM flyway_schema_history WHERE version = '7'")
    assert len(v7_res) == 1 and v7_res[0]["success"] is True, f"Flyway V7 failed or absent: {v7_res}"
    print(f"  ✓ Flyway V7 '{v7_res[0]['description']}' confirmed applied (success={v7_res[0]['success']})")

    # 2. Operational Records Timestamp & Hash Recalculation
    print("\n[2/3] Retrieving operational audit rows and verifying SHA-256 chain...")
    # ISO-8601 UTC timestamp format: YYYY-MM-DDTHH24:MI:SS.MSZ
    query = """
        SELECT sequence_number,
               to_char(event_timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS event_timestamp,
               event_type, agent_id, user_id, actor_id, tool_name,
               redacted_arguments_json, policy_rule_id, policy_rule_name,
               decision, approval_id, outcome, latency_ms, error_message,
               prev_record_hash, record_hash
        FROM audit_log
        ORDER BY sequence_number ASC
    """
    records = run_psql_json(query)
    tip_res = run_psql_json("SELECT last_sequence, last_hash FROM audit_chain_state WHERE id = 1")
    tip_seq = tip_res[0]["last_sequence"]
    tip_hash = tip_res[0]["last_hash"]

    print(f"  Operational rows loaded: {len(records)}, State tip: seq={tip_seq}, hash={tip_hash[:16]}...")
    valid, msg, bad_seq = verify_chain(records, tip_seq, tip_hash)
    assert valid, f"Operational chain verification failed: {msg}"
    print(f"  ✓ Operational audit trail verified intact: {msg}")

    # 3. Isolated Disposable Tampering Tests (never touches operational rows)
    print("\n[3/3] Running isolated disposable tampering tests against verification algorithm...")

    # Scenario A: Payload Modification
    tampered_records = copy.deepcopy(records)
    tampered_records[1]["tool_name"] = "unauthorized_database_dump"
    valid_mod, msg_mod, bad_seq_mod = verify_chain(tampered_records, tip_seq, tip_hash)
    assert not valid_mod and bad_seq_mod == 2, f"Expected modification detection at seq 2, got {bad_seq_mod}: {msg_mod}"
    print(f"  ✓ Tampered payload detected: '{msg_mod}' (identified sequence {bad_seq_mod})")

    # Scenario B: Middle Row Deletion (Sequence Gap)
    gap_records = copy.deepcopy(records)
    del gap_records[1] # Delete seq 2
    valid_gap, msg_gap, bad_seq_gap = verify_chain(gap_records, tip_seq, tip_hash)
    assert not valid_gap and bad_seq_gap == 3, f"Expected gap detection at seq 3, got {bad_seq_gap}: {msg_gap}"
    print(f"  ✓ Middle row deletion detected: '{msg_gap}' (identified sequence {bad_seq_gap})")

    # Scenario C: Tail Row Deletion (Tip Discrepancy)
    tail_records = copy.deepcopy(records)
    tail_records.pop() # Delete last record (seq 4), but tip still points to 4
    valid_tail, msg_tail, bad_seq_tail = verify_chain(tail_records, tip_seq, tip_hash)
    assert not valid_tail and bad_seq_tail == 3, f"Expected tail mismatch detection at seq 3, got {bad_seq_tail}: {msg_tail}"
    print(f"  ✓ Tail row deletion detected: '{msg_tail}' (identified discrepancy)")

    print("\n" + "=" * 70)
    print("REAL POSTGRESQL VERIFICATION & TAMPER DETECTION SUCCESSFUL")
    print("Operational audit records preserved untouched.")
    print("=" * 70)

if __name__ == "__main__":
    main()
