-- Policy Engine Schema (FR3)
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

-- Seed initial policies for FR3, FR4, FR9, and QA1 scenarios
INSERT INTO policies (id, name, agent_pattern, tool_pattern, conditions_json, action, dry_run, priority, version, enabled)
VALUES
    ('pol-read-orders', 'Customer Order Self-Read Policy', '*', 'orders_get', '[{"field":"customerId","operator":"EQUALS_USER_ID"}]', 'ALLOW', false, 10, 1, true),
    ('pol-small-refund', 'Finance Bot Low Value Refund', 'agent-finance-*', 'orders_refund', '[{"field":"amount","operator":"LTE","value":2000}]', 'ALLOW', false, 20, 1, true),
    ('pol-large-refund', 'High Value Refund Approval', 'agent-finance-*', 'orders_refund', '[{"field":"amount","operator":"GT","value":2000}]', 'REQUIRE_APPROVAL', false, 30, 1, true),
    ('pol-dry-run-test', 'Dry Run Audit Policy', 'agent-test-*', '*', '[]', 'DENY', true, 50, 1, true)
ON CONFLICT (id) DO NOTHING;
