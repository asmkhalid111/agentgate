-- Audit Trail & Tamper-Evident Hash Chain Schema (FR7)

CREATE TABLE IF NOT EXISTS audit_chain_state (
    id INT PRIMARY KEY,
    last_sequence BIGINT NOT NULL,
    last_hash VARCHAR(64) NOT NULL
);

-- Seed genesis state tip using standard SQL (portable across PostgreSQL and H2)
INSERT INTO audit_chain_state (id, last_sequence, last_hash)
SELECT 1, 0, '0000000000000000000000000000000000000000000000000000000000000000'
WHERE NOT EXISTS (SELECT 1 FROM audit_chain_state WHERE id = 1);

CREATE TABLE IF NOT EXISTS audit_log (
    sequence_number BIGINT PRIMARY KEY,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    actor_id VARCHAR(128),
    tool_name VARCHAR(100) NOT NULL,
    redacted_arguments_json TEXT NOT NULL DEFAULT '{}',
    policy_rule_id VARCHAR(64),
    policy_rule_name VARCHAR(100),
    decision VARCHAR(40) NOT NULL,
    approval_id VARCHAR(64),
    outcome VARCHAR(40) NOT NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    prev_record_hash VARCHAR(64) NOT NULL,
    record_hash VARCHAR(64) NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_agent_tool ON audit_log(agent_id, tool_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_approval_id ON audit_log(approval_id);
