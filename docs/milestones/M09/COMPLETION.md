# Startup and scope verification

On 2026-11-27, docker compose up --build -d --wait completed successfully on the existing local deployment. Maven 3.9.9 with Java 21 built both Spring Boot applications inside the shared Dockerfile build stage. Its allowlist excludes host build artifacts and local environment files. Docker Compose reported all five services healthy. Both application readiness endpoints returned HTTP 200 and UP. The build used public images and Maven Central.

## C1 memory evidence

| Service | Idle memory after the readiness and onboarding checks |
|---|---:|
| Gateway | 360.80 MiB |
| Demo tools | 152.80 MiB |
| PostgreSQL | 24.75 MiB |
| Redis | 13.02 MiB |
| Keycloak | 510.50 MiB |
| Total | 1,061.87 MiB |

The Docker engine reported 8,157,552,640 bytes of available memory. The host reported 16,835,387,392 bytes installed. This run demonstrates the startup command and steady-state container footprint on that host. A cold build and startup on a physical 8 GB host remain unverified. The Compose command used the existing local configuration and persistent data.

## C2 configuration review

Validated typed properties are already present for rate limits, resilience, and MCP downstream routing. The retained Maven reports include five passing rate-limit binding and resilience configuration tests. The tracked-file check found the environment template and no matching tracked .env or private key files. The realm and verification scripts still contain literal demo credentials for some identities. Environment-only fixture provisioning and a check for credentials in tracked content are needed before closing C2.

## C3 live API checks

| Request | Observed result |
|---|---|
| Anonymous gateway GET /v3/api-docs | 401 |
| Gateway GET /v3/api-docs with administrator bearer token | 200 JSON |
| Administrator GET /api/v1/registry/tools?page=0&size=1 | 200, at most one item |
| Administrator GET /api/v1/registry/tools?size=101 | 400 application/problem+json |

The demo-tools HTTP surface lacks equivalent Spring Security, OpenAPI, and Problem Details coverage. The existing gateway C3 evidence retains its scope.

## QA5 readiness

The automated rehearsal on the source-built stack took 0.82 seconds and removed the temporary tool and policy. The independent participant trial remains open. Use the [participant walkthrough](../../adoption/QA5_WALKTHROUGH.md).
