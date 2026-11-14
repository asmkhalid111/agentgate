# Mini-ATAM utility tree (C7) — candidate scenarios

At least six concrete scenarios, with sensitivity and trade-off points, are required. These are **targets from the handout**, not measured results. In M10 attach evidence and ratings.

| Scenario | Quality driver | Stimulus and response target | Sensitivity/trade-off to evaluate | Evidence |
|---|---|---|---|---|
| S1 | Security | Malicious MCP tool call denied/held; 30/30 red-team pass | Default-deny vs legitimate false blocks | PENDING |
| S2 | Performance | 100 cps, gateway-added p95 <=30ms | Audit persistence and policy lookup vs latency | PENDING |
| S3 | Auditability | Explain any call <1 minute; tampering detected | Hash-chain serialization vs throughput | PENDING |
| S4 | Availability | Policy store down; high/medium fail closed, low uses cache <=5m | Availability vs policy freshness | PENDING |
| S5 | Modifiability | Unknown developer adds REST tool <10m, no code | Importer convenience vs validation/SSRF | PENDING |
| S6 | Safety/operability | Kill agent/tool/writes immediately and cancel approvals | Cache speed vs emergency consistency | PENDING |
| S7 | Resilience | Downstream fails; timeout/circuit opens and hides tool until recovered | Fast suppression vs false-negative health | PENDING |
| S8 | Resource fit | Full Compose up on 8GB laptop | Memory/observability depth vs simplicity | PENDING |

In final tree classify priorities and write at least three sensitivity and trade-off discussions based on evidence.
