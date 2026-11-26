# ADR 0003 Commit audit events to a PostgreSQL hash chain

## Context
FR7 and QA3 require explainable calls and verifiable audit integrity. Write admission must persist an intent before dispatch.

## Options
Use application logs, enqueue audit events for eventual persistence, or commit a PostgreSQL hash chain with serialized updates.

## Decision
Use AuditRecorder with a transactional JDBC repository and a SHA-256 hash chain. Commit write intents before dispatch and verify the stored sequence through the audit verification service.

## Consequences
The chain identifies changes to recorded events and database transactions protect audit consistency. Serializing updates at a single chain head adds contention. Any future batching or partitioning design must preserve write-intent guarantees and verifiability.

## Measured evidence
The two live audit lookups took 0.332 seconds combined. Three isolated tamper checks detected a row edit and middle and tail deletion. The original 18,000-call QA2 run measured 342.05 ms p95 overhead and 314.22 ms p95 audit lock wait, against the 30 ms gateway target. [QA3 evidence](../../quality/QA3_AUDITABILITY.md) · [QA2 acceptance](../../quality/performance/QA2_FINAL_ACCEPTANCE.md)
