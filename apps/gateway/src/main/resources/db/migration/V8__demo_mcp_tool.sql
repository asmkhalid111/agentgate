-- Seed one real downstream MCP tool (inventory_check) served by demo-tools (/mcp) for FR1 interoperability
INSERT INTO registered_tools (
    id, name, description, input_schema, risk_level, owning_team, endpoint_url, protocol_type, http_method, enabled
)
VALUES (
    'tool-inventory-check',
    'inventory_check',
    'Check warehouse inventory availability for a product SKU via downstream MCP',
    '{"type":"object","properties":{"sku":{"type":"string"}},"required":["sku"]}',
    'LOW',
    'Inventory Team',
    'http://demo-tools:8082/mcp',
    'MCP',
    'POST',
    true
)
ON CONFLICT (name) DO NOTHING;

-- Authorize only agent-support-bot; preserve default-deny for all other agents
INSERT INTO tool_agent_permissions (tool_name, agent_id)
VALUES ('inventory_check', 'agent-support-bot')
ON CONFLICT (tool_name, agent_id) DO NOTHING;

-- Explicit ALLOW policy for agent-support-bot calling inventory_check
INSERT INTO policies (id, name, agent_pattern, tool_pattern, conditions_json, action, dry_run, priority, version, enabled)
VALUES (
    'pol-allow-inventory-check',
    'Support Bot Inventory Check',
    'agent-support-bot',
    'inventory_check',
    '[]',
    'ALLOW',
    false,
    15,
    1,
    true
)
ON CONFLICT (id) DO NOTHING;
