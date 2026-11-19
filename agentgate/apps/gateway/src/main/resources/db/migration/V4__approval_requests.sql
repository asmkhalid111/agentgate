-- Human Approval State Persistence (FR4)
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
    decision_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_approval_requests_status ON approval_requests(status);
CREATE INDEX IF NOT EXISTS idx_approval_requests_expires_at ON approval_requests(expires_at);
CREATE INDEX IF NOT EXISTS idx_approval_requests_agent_id ON approval_requests(agent_id);
