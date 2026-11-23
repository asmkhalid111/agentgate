INSERT INTO registered_tools (id, name, description, input_schema, risk_level, owning_team, endpoint_url, protocol_type, http_method, enabled, created_at, updated_at)
VALUES ('tool-benchmark-read', 'benchmark_read', 'Benchmark read tool', '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'LOW', 'Benchmark Team', 'http://benchmark-demo-tools:8082/api/orders/{id}', 'REST', 'GET', true, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET endpoint_url = EXCLUDED.endpoint_url;

INSERT INTO tool_agent_permissions (tool_name, agent_id)
VALUES ('benchmark_read', 'agent-support-bot')
ON CONFLICT DO NOTHING;

INSERT INTO policies (id, name, agent_pattern, tool_pattern, conditions_json, action, dry_run, priority, version, enabled, updated_at)
VALUES ('pol-benchmark-read', 'Benchmark Read Allow Policy', '*', 'benchmark_read', '[]', 'ALLOW', false, 10, 1, true, NOW())
ON CONFLICT (id) DO UPDATE SET updated_at = NOW();
