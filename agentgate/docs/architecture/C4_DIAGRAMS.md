# AgentGate architecture diagrams

## System context

~~~mermaid
flowchart LR
  Agent["Registered agent"]
  User["End user"]
  Approver["Human approver"]
  Admin["Human operator or auditor"]
  Gate["AgentGate · governance gateway"]
  IdP["Keycloak · identity provider"]
  Tools["Downstream REST and MCP tool services"]
  Webhook["Approval webhook receiver"]
  Agent -->|Discover and invoke tools| Gate
  User -->|Authorize delegated access| IdP
  Agent -->|OAuth client credentials| IdP
  Approver -->|Review pending calls| Gate
  Admin -->|Configure controls and verify audit| Gate
  Gate -->|Retrieve signing keys and validate identity| IdP
  Gate -->|Dispatch governed requests| Tools
  Gate -->|Notify of pending approval| Webhook
~~~

## Containers in the local deployment

~~~mermaid
flowchart LR
  Client["Agent or human browser"]
  subgraph Compose["Docker Compose"]
    G["Gateway · Spring Boot MVC · 8080"]
    K["Keycloak · OpenID Connect"]
    P[("PostgreSQL · registry, policy, approvals, audit")]
    R[("Redis · quotas and rate counters")]
    D["Demo tools · REST, MCP, webhook · 8082"]
  end
  Client -->|HTTP, MCP Streamable HTTP| G
  Client -->|OAuth or browser sign-in| K
  G -->|OIDC and JWKS| K
  G -->|JDBC and Flyway| P
  G -->|Atomic Redis operations| R
  G -->|HTTP, MCP Streamable HTTP| D
~~~

## Gateway components

~~~mermaid
flowchart TD
  Client["Agent"]
  Human["Operator or approver"]
  Identity["Spring Security · SecurityConfig and DelegationFilter"]
  Mcp["MCP ingress · McpConfig and DynamicGovernedToolCallback"]
  Admin["Registry, policy, approval, kill-switch and audit controllers"]
  Pipeline["Governed execution · ToolExecutionPipeline"]
  Rules["Registry, StructuredPolicyEvaluator and kill switches"]
  Guards["Schema, response redaction and truncation guards"]
  Approval["ApprovalService and ApprovedToolExecutionListener"]
  Limits["RedisRateLimiter and write cap"]
  Audit["AuditRecorder and AuditVerificationService"]
  Dispatch["Circuit breaker and REST or MCP adapter"]
  Data[("PostgreSQL")]
  Counters[("Redis")]
  Target["Tool service"]
  Client --> Identity
  Human --> Identity
  Identity --> Mcp
  Identity --> Admin
  Mcp --> Pipeline
  Pipeline --> Rules
  Pipeline --> Guards
  Pipeline --> Approval
  Approval -->|Resume through governed execution| Pipeline
  Pipeline --> Limits
  Pipeline --> Audit
  Pipeline --> Dispatch
  Admin --> Rules
  Admin --> Approval
  Admin --> Audit
  Rules --> Data
  Approval --> Data
  Audit --> Data
  Limits --> Counters
  Dispatch --> Target
~~~

Deployment and application evidence is available in the [Compose definition](../../compose.yaml) and [gateway implementation](../../apps/gateway/src/main/java/com/agentgate/gateway).
