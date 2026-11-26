# ADR 0001 Use MVC and adapters for tool transport

## Context
AgentGate exposes one governed MCP ingress and calls both legacy REST and MCP tools. The round trip contributes to QA2 gateway overhead.

## Options
Spring MVC with the existing synchronous governance path, Spring WebFlux end to end, or protocol-specific execution pipelines.

## Decision
Use Spring MVC for the gateway with the Spring AI Streamable HTTP server. Route calls through one execution pipeline with REST and MCP adapters. Keep transport observations tied to the same trace.

## Consequences
One path applies policies and audits both protocols. Blocking calls still require explicit timeouts and resource limits. A WebFlux rewrite would change the programming model and would require separate end-to-end measurements.

## Measured evidence
The full QA2 benchmark measured MVC/MCP ingress at 8.55 ms p95 and audit lock wait at 314.22 ms p95. Live REST and MCP calls also produced matching trace IDs across the two applications. These are measurements of the selected stack. [QA2 acceptance](../../quality/performance/QA2_FINAL_ACCEPTANCE.md) · [Live traces](../../milestones/M05/C4_LIVE_EVIDENCE.txt)
