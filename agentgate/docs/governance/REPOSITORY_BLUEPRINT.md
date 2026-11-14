# Repository Blueprint (target after M01)

```text
agentgate/
├── AGENTS.md
├── README.md
├── START_HERE.md
├── pom.xml                         # aggregator + dependency management
├── mvnw / mvnw.cmd / .mvn/        # Maven Wrapper
├── compose.yaml
├── .env.example                    # placeholders, no passwords in Git
├── .gitignore
├── apps/
│   ├── gateway/                    # one modular Spring Boot monolith
│   │   ├── pom.xml
│   │   └── src/main/java/.../gateway/
│   │       ├── identity/           # OAuth client identity, trusted user context
│   │       ├── registry/           # tools, schemas, OpenAPI import
│   │       ├── mcp/                # MCP server transport & permitted tool listing
│   │       ├── execution/          # ONE controlled call pipeline
│   │       ├── policy/             # evaluation, versions, cache, playground
│   │       ├── guard/              # input validation, output redaction
│   │       ├── approval/           # state machine, notification, web view
│   │       ├── limits/             # quotas, rate checks, write limits
│   │       ├── audit/              # append, search, export, verification
│   │       ├── resilience/         # downstream timeout, breaker, health
│   │       ├── admin/              # minimal UI/controllers
│   │       ├── observability/      # logs, metrics, tracing
│   │       └── common/             # minimal truly shared types
│   └── demo-tools/                # locally hosted mock MCP + REST endpoints
├── infra/                          # Keycloak realm provisioning, config etc.
├── scripts/                        # smoke, redteam, latency, audit, adoption
├── docs/
│   ├── governance/                 # requirements, roadmap, state, logs
│   ├── architecture/
│   │   ├── adr/                    # ADR-0001.. (approved, measured)
│   │   └── c4/                     # context/container/component diagrams
│   ├── quality/                    # utility tree, patterns, benchmarks methodology
│   ├── milestones/
│   │   └── Mxx/                    # PLAN.md, COMPLETION.md, REVIEW.md
│   └── evidence/                   # machine-readable raw data and charts
├── prompts/                        # reproducible operator prompts
├── .agents/skills/                 # Antigravity-recognized procedures
└── .github/workflows/ci.yml        # automated verification
```

## Folder principle
**Organize code by feature (business responsibility); organize process documents by milestone.** Never create `src/main/java/milestone3/`. An M05 approval handler belongs in `approval/`, not in a milestone-specific code directory. Tests mirror feature packages and include full gateway integration suites.

Package names are a proposal and subject to M00 design review; names are not API contracts.
