# Pattern justification

| Pattern | Requirement served | Participating classes | Rejected alternative and reason |
|---|---|---|---|
| Adapter | FR1 registration and governed invocation of REST and MCP tools | ToolAdapter, RestToolAdapter, McpDownstreamToolAdapter | Put protocol-specific execution into controllers. This would duplicate governance and timeout handling. |
| Proxy | FR1, FR2 and FR5 access through one governed entry point | DynamicGovernedToolCallback, ToolExecutionPipeline | Hand out downstream endpoints to callers. This would bypass gateway enforcement. |
| Pipeline | FR3–FR8 ordered policy, guards, approval, quota, audit and emergency checks | ToolExecutionPipeline, JsonSchemaValidationGuard, PiiRedactionGuard, ResponseTruncationGuard | Put all checks in adapters. Every transport would have to enforce the same ordering on its own. |
| Repository | FR1, FR3 and FR7 persistence of tools, rules and audit records | RegisteredToolRepository, PolicyRepository, AuditRepository, JdbcAuditRepository | Embed database queries in controllers. That would mix HTTP contracts with storage and transaction behavior. |
| State machine | FR4 approval progression and execution tracking | ApprovalService, ApprovalStatus, ExecutionState, ApprovalRequestRepository | Track approval and dispatch as a single boolean. That loses expiry and execution progress. |
| Observer | FR1 discovery updates and FR4 post-commit execution and notification | McpToolRegistrationListener, ApprovedToolExecutionListener, ApprovalWebhookNotifier | Contact listeners inside the approval transaction. A slow notification could extend the transaction and an uncommitted approval could escape. |
| Circuit Breaker | FR10 health-driven suppression and recovery | ToolCircuitBreaker, ToolCircuitBreakerRegistry, ToolFailureClassifier, McpCircuitBreakerNotificationListener | Dispatch to an unhealthy tool on every call. Repeated failures would consume request time and resources. |
| Policy snapshot cache with scheduled refresh | FR3 and QA4 policy freshness and low-risk fallback | StructuredPolicyEvaluator, PolicyRepository | Query policy storage for every call. This would couple every invocation to storage availability. |

[Participating gateway classes](../../apps/gateway/src/main/java/com/agentgate/gateway)
