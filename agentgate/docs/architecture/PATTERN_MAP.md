# Pattern justification ledger (C8)

**Candidates only — not claims that code exists.** Populate participating *real* classes and a rejected alternative after implementing each pattern.

| Candidate pattern | Project-1 need | Candidate location | Participating classes (actual after coding) | Rejected alternative + reason | Evidence |
|---|---|---|---|---|---|
| Chain of Responsibility | FR3/FR5 ordered policy + guards | `policy`, `guard` | PENDING | PENDING | PENDING |
| Strategy | FR3 configurable evaluation, FR5 redactors | `policy`, `guard` | PENDING | PENDING | PENDING |
| Decorator / Proxy | Governed tool invocation | `execution` | PENDING | PENDING | PENDING |
| Adapter | MCP + REST transport | `registry`/`execution` | PENDING | PENDING | PENDING |
| State | FR4 approval lifecycle | `approval` | PENDING | PENDING | PENDING |
| Observer | FR4 notifications, FR6 alerts, FR7 audit events | `approval`/`audit` | PENDING | PENDING | PENDING |
| Command | Invocations as auditable, replay-safe requests | `execution` | PENDING | PENDING | PENDING |

Only document patterns that are genuinely implemented and tied to a requirement; otherwise remove candidate rows.
