-- Tool Registry & Permissions Schema (FR1)
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

-- Seed baseline tools for demo and testing
INSERT INTO registered_tools (id, name, description, input_schema, risk_level, owning_team, endpoint_url, protocol_type, http_method, enabled)
VALUES
    ('tool-orders-get', 'orders_get', 'Retrieve order details by order ID', '{"type":"object","properties":{"orderId":{"type":"string"}},"required":["orderId"]}', 'LOW', 'Orders Team', 'http://demo-tools:8082/api/orders/{orderId}', 'REST', 'GET', true),
    ('tool-orders-refund', 'orders_refund', 'Process customer order refund', '{"type":"object","properties":{"orderId":{"type":"string"},"amount":{"type":"number"},"customerId":{"type":"string"}},"required":["orderId","amount"]}', 'HIGH', 'Finance Team', 'http://demo-tools:8082/api/orders/{orderId}/refund', 'REST', 'POST', true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO tool_agent_permissions (tool_name, agent_id)
VALUES
    ('orders_get', 'agent-support-bot'),
    ('orders_get', 'agent-finance-bot'),
    ('orders_refund', 'agent-finance-bot')
ON CONFLICT (tool_name, agent_id) DO NOTHING;
