# QA5 independent developer trial

**Task:** Register a read-only readiness tool for the support agent using the running local gateway. The participant should be a developer who has never worked with AgentGate. Record elapsed time and any help needed. The target is under ten minutes with zero application code changes.

## Setup before timing

The observer starts the existing Compose stack, supplies an API client with a local human administrator bearer token and a separate authenticated support-agent MCP session, and picks a fresh operation name beginning with qa5_readiness_. Supply this guide and the operation name. Keep credentials out of the evidence. The participant performs all registration and policy steps.

## Timed steps

1. Start the timer. In the API client, POST to http://localhost:8080/api/v1/registry/tools/import-openapi as administrator. Use the following JSON, replacing QA5NAME with the assigned operation name.

~~~json
{"owningTeam":"QA5","baseUrl":"http://demo-tools:8082","spec":"{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Demo readiness\",\"version\":\"1\"},\"paths\":{\"/actuator/health/readiness\":{\"get\":{\"operationId\":\"QA5NAME\",\"summary\":\"Read demo readiness\",\"responses\":{\"200\":{\"description\":\"Readiness\"}}}}}}"}
~~~

2. Inspect the returned tool and record its ID. Confirm that its risk is LOW, appropriate for a read-only status query. The importer assigns this risk automatically.
3. POST to /api/v1/registry/tools/QA5NAME/permissions with {"agentId":"agent-support-bot"}.
4. POST to /api/v1/policies with the following body, replacing QA5NAME throughout.

~~~json
{"id":"allow-QA5NAME","name":"Allow QA5 readiness","agentPattern":"agent-support-bot","toolPattern":"QA5NAME","conditions":[],"action":"ALLOW","dryRun":false,"priority":10,"version":1,"enabled":true}
~~~

5. In the support-agent MCP session, call QA5NAME with an empty arguments object. Stop the timer when the tool reports UP. Capture the result and elapsed time.

## Observer record

Record the application revision, participant alias, prior familiarity, start and finish times, elapsed seconds, code changes, help given, returned risk and tool outcome. Retain unsuccessful attempts. After recording the result, delete /api/v1/policies/allow-QA5NAME and /api/v1/registry/tools/{returned-tool-id} with the administrator token.

## Rehearsal status

The automated local rehearsal on 2026-11-26 successfully imported the tool, confirmed LOW risk, granted support-agent permission, created the scoped policy, and received UP through MCP. The temporary policy and registration were removed. The independent participant result is pending.
