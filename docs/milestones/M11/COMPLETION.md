# Demo API C3 checkpoint

The demo REST, MCP, webhook, and OpenAPI routes require the local demo service credential in the X-AgentGate-Demo-Token header. Spring Security covers all demo requests, with explicit public access to health, readiness and liveness. OpenAPI documentation is available to authenticated callers. The demo service has no list routes.

Demo webhook fields, order IDs and refund amounts are validated. Spring MVC returns RFC 9457 responses for validation failures and the simulated HTTP failures. Spring Security returns Problem Details for HTTP access errors.

The gateway sends the demo credential only when scheme, hostname and effective port match the configured demo origin. Requests with URL user information cannot receive it. REST, MCP and webhook clients disable redirect following. The benchmark gateway has its own demo origin configuration. A direct baseline client loads the same token from the environment.

## Verification

- The complete Maven verify run passed 305 gateway tests, one Testcontainers integration test and two demo tests. The origin test covers a different host, a different port, a different scheme and URL user information. In the first focused run the MVC slice lacked HttpSecurity. Enabling web security on the configuration class fixed the slice and the focused rerun passed.
- The existing Compose stack rebuilt from source and all five services were healthy. The demo runner verified anonymous HTTP 401 Problem Details for orders, MCP, webhook and OpenAPI. Readiness remained public, authenticated OpenAPI loaded, invalid webhook and refund requests returned HTTP 400 Problem Details, and the simulated order failure returned HTTP 500 Problem Details.
- A governed call reached the protected inventory MCP tool. A newly created approval reached the protected demo webhook through the gateway notifier and the test request was rejected afterward.
- The live delegated-order check passed through the protected REST adapter. The QA5 automated rehearsal also passed in 0.44 seconds. The independent participant trial remains pending.
- The temporary benchmark stack passed a direct authenticated read and a governed read, then was removed. The original stack was restored and all five services were healthy. This was a connectivity check. The QA2 performance result remains open.

Raw local logs are in target-c3-demo-verify.log, target-c3-demo-compose.log and target-c3-benchmark.log. Reproduce the gateway-to-demo checks with the scripts below.

- [Demo API and webhook checks](../../../scripts/verify_demo_api.py)
- [Delegated and governed REST check](../../../scripts/verify_delegated_orders.py)
- [Automated onboarding rehearsal](../../../scripts/verify_qa5_onboarding.py)

## Resume point

C3 has local completion evidence. The working count is 21 of 27 including the owner-accepted C1. C5 still needs a hosted CI run. QA5 and C9 await an independent participant result. QA2, the instructor-issued C10 change request and the C12 live defence remain open.
