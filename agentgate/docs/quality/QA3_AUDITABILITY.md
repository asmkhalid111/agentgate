# QA3 auditability verification

On 2026-11-26 at application revision 619b546, an automated local auditor walkthrough authenticated as human-admin and searched GET /api/v1/admin/audit for agent-finance-bot, orders_get, and each outcome. Authentication, both searches, JSON parsing, and identifying both records took 0.332 seconds combined. The course threshold is under 60 seconds. This measures an automated walkthrough with known search terms.

| Sequence | Agent | User subject | Order | Rule | Outcome |
|---|---|---|---|---|---|
| 305 | agent-finance-bot | ce4c7e83-0940-417d-89f4-699ab6afdb98 | 4314c7b5-31bc-4e9f-8120-9d9cf5c085ff | pol-read-orders | SUCCESS |
| 306 | agent-finance-bot | ce4c7e83-0940-417d-89f4-699ab6afdb98 | 2c5fa69f-9353-46fd-93b2-cb6cac91684d | DEFAULT_DENY | BLOCKED |

The isolated H2 integration suite detected modified row content, a deleted middle row, and a deleted tail row. Reproduce from agentgate with mvnw.cmd -pl apps/gateway "-Dtest=AuditRecorderIntegrationTest#tamperDetection_*" test. The run recorded 3 tests, 0 failures, 0 errors, and 0 skipped. Raw Maven output is available locally in target-qa3-tamper.log.
