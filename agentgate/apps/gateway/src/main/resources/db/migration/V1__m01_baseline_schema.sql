-- Initial Flyway baseline schema for Milestone M01
CREATE TABLE IF NOT EXISTS agent_client (
    id VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS m01_schema_marker (
    installed_on TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    milestone VARCHAR(16) NOT NULL DEFAULT 'M01',
    status VARCHAR(32) NOT NULL DEFAULT 'INITIALIZED'
);

INSERT INTO agent_client (id, client_id, display_name, active)
VALUES 
    ('agent-finance-01', 'agent-finance-bot', 'Finance Automation Bot', true),
    ('agent-support-01', 'agent-support-bot', 'Customer Support Bot', true)
ON CONFLICT (client_id) DO NOTHING;

