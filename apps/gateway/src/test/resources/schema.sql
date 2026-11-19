CREATE TABLE IF NOT EXISTS agent_client (
    id VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS registered_tools (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    input_schema TEXT NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW',
    owning_team VARCHAR(100) NOT NULL,
    endpoint_url VARCHAR(500) NOT NULL,
    protocol_type VARCHAR(20) NOT NULL DEFAULT 'REST',
    http_method VARCHAR(10) NOT NULL DEFAULT 'POST',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tool_agent_permissions (
    tool_name VARCHAR(100) NOT NULL,
    agent_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (tool_name, agent_id)
);

CREATE TABLE IF NOT EXISTS policies (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    agent_pattern VARCHAR(100) NOT NULL,
    tool_pattern VARCHAR(100) NOT NULL,
    conditions_json TEXT,
    action VARCHAR(20) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    priority INT NOT NULL DEFAULT 100,
    version INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS approval_requests (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    tool_name VARCHAR(100) NOT NULL,
    policy_rule_id VARCHAR(64) NOT NULL,
    original_arguments_json TEXT NOT NULL,
    approved_arguments_json TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    decided_by VARCHAR(128),
    decision_reason TEXT,
    execution_state VARCHAR(20) NOT NULL DEFAULT 'NOT_DISPATCHED',
    executed_at TIMESTAMP WITH TIME ZONE,
    execution_result_json TEXT,
    execution_error TEXT
);

CREATE TABLE IF NOT EXISTS kill_switches (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    target VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    reason TEXT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_kill_switch_type_target UNIQUE (type, target)
);

CREATE INDEX IF NOT EXISTS idx_kill_switches_lookup ON kill_switches(active, type, target);

