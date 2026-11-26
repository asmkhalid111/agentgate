# AgentGate

AgentGate is the CSE 444 governance gateway for MCP and REST tool calls. Docker Compose runs the Java 21 and Spring Boot 4 gateway, demo tools, Keycloak, PostgreSQL, and Redis locally.

## Start the demo

Use Docker Desktop with Linux containers and open a terminal in the AgentGate application directory. For a new installation, create the private environment file with Python 3.

~~~console
python scripts/init_local_env.py
~~~

The script generates local PostgreSQL and Keycloak credentials and preserves an existing .env. On an existing checkout, run python scripts/init_local_env.py --add-missing to add newly required keys. To configure values manually, use the keys in .env.example. For existing persistent databases and realms, use the credentials already configured for those services.

~~~console
docker compose up --build -d --wait
~~~

Compose builds both applications from source with Maven. A first build downloads public images and dependencies. Once it finishes, the gateway is on localhost:8080, the demo tool service is on localhost:8082, and Keycloak is on localhost:8180.

- Check gateway readiness at http://localhost:8080/actuator/health/readiness.
- Use /mcp on the gateway for governed agent requests and /approvals for the browser approval interface.
- Use the administrator role to read /v3/api-docs or inspect audit records under /api/v1/admin/audit.
- Demo OpenAPI at localhost:8082/v3/api-docs accepts the local service credential in the X-AgentGate-Demo-Token header. Load its value privately from the environment or .env in your API client.
- Use the [QA5 walkthrough](docs/adoption/QA5_WALKTHROUGH.md) for the independent onboarding trial.

[Start here](START_HERE.md) · [Roadmap](docs/governance/ROADMAP.md) · [Requirements matrix](docs/governance/REQUIREMENTS_MATRIX.md) · [Architecture diagrams](docs/architecture/C4_DIAGRAMS.md) · [Quality evidence](docs/quality/QA_EVIDENCE_REPORT.md)
