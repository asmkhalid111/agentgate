# AgentGate

AgentGate is the CSE 444 governance gateway for MCP and REST tool calls. Docker Compose runs the Java 21 and Spring Boot 4 gateway, demo tools, Keycloak, PostgreSQL, and Redis locally.

> **[Read the Architecture Rationale](docs/architecture/ARCHITECTURE_RATIONALE.md)** — why AgentGate is structured as a modular monolith with a single `ToolExecutionPipeline`, Keycloak `azp`-bound delegation, SHA-256 audit chaining, and risk-aware policy fallback.

## Verification & Source Provenance

- **Tested source baseline:** `4631aa5` (on private `master`; application/test code at `85f8a5b`).
- **Latest local verification (`.\mvnw.cmd -B verify` at `85f8a5b`):** **314 tests passed** (`0` failures, `0` errors, `0` skips), including `gateway` Surefire (`310` tests with `6/6` ArchUnit rules), `gateway` Failsafe (`2` Testcontainers ITs including real-PostgreSQL QA4 policy-read outage), and `demo-tools` (`2` tests).
- **Earlier private hosted CI run (`3c5fad4` on `review/agentgate-ci`, private repo artifact):** **308 Maven tests passed**, QA1 `38/38` malicious blocked/held and `0/200` false positives, QA3 `0.051 s` lookup, and QA5 automated rehearsal `0.36 s`.
- **QA2 Performance (Failed & Deferred):** The hosted k6 run at `3c5fad4` measured **423.45 ms p95** gateway overhead with **873 dropped iterations** at 100 calls/sec against the **30 ms p95** target (primarily due to serialized audit chain-head updates). See [Quality evidence](docs/quality/QA_EVIDENCE_REPORT.md).

## Start the demo

Use Docker Desktop with Linux containers and open a terminal in the AgentGate application directory. For a new installation, create the private environment file with Python 3.

~~~console
python scripts/init_local_env.py
~~~

The script generates local PostgreSQL and Keycloak credentials and preserves an existing `.env`. On an existing checkout, run `python scripts/init_local_env.py --add-missing` to add newly required keys. To configure values manually, use the keys in `.env.example`. For existing persistent databases and realms, use the credentials already configured for those services.

~~~console
docker compose up --build -d --wait
~~~

Compose builds both applications from source with Maven. A first build downloads public images and dependencies. Once it finishes, the gateway is on `localhost:8080`, the demo tool service is on `localhost:8082`, and Keycloak is on `localhost:8180`.

- Check gateway readiness at `http://localhost:8080/actuator/health/readiness`.
- Use `/mcp` on the gateway for governed agent requests and `/approvals` for the browser approval interface.
- Use the administrator role to read `/v3/api-docs` or inspect audit records under `/api/v1/admin/audit`.
- Demo OpenAPI at `localhost:8082/v3/api-docs` accepts the local service credential in the `X-AgentGate-Demo-Token` header. Load its value privately from the environment or `.env` in your API client.
- Use the [QA5 walkthrough](docs/adoption/QA5_WALKTHROUGH.md) for the independent onboarding trial.

[Architecture rationale](docs/architecture/ARCHITECTURE_RATIONALE.md) · [Architecture diagrams](docs/architecture/C4_DIAGRAMS.md) · [Pattern justification](docs/architecture/PATTERN_MAP.md) · [Requirements matrix](docs/governance/REQUIREMENTS_MATRIX.md) · [Quality evidence](docs/quality/QA_EVIDENCE_REPORT.md) · [Start here](START_HERE.md) · [Roadmap](docs/governance/ROADMAP.md)
