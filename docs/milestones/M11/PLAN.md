# Demo API C3 verification plan

The gateway has existing C3 evidence. The downstream demo service in Compose publishes REST, webhook, MCP and Actuator endpoints. Its current app lacks a Spring Security filter chain and OpenAPI coverage. Its approval webhook accepts an unvalidated map.

## Proposed scope

- Add Spring Security and springdoc to demo-tools. Define explicit access rules for probes, documentation, and protected REST, MCP and webhook traffic.
- Use an environment-provisioned local service credential for gateway calls to demo-tools on the Compose network. Bind it to the configured demo origin in REST, MCP and webhook clients. Verify credential isolation for independently registered endpoints. Preserve the local benchmark and smoke-test paths.
- Add Bean Validation to the webhook and applicable demo HTTP inputs. Return RFC 9457 responses for HTTP validation and access errors. Verify whether any demo route returns a list that needs pagination.

## Acceptance checks

Prove that anonymous demo REST, MCP, webhook and OpenAPI requests are denied, approved service calls succeed, required probes remain reachable, and an invalid webhook returns 400 with application/problem+json. Repeat the gateway REST and MCP calls, the approval notification and QA5 rehearsal under the new configuration. Check that the demo credential never travels to another downstream host. Run Maven verification and record the results.

## Risks

Protecting demo-tools changes the existing callers. Exact origin matching and redirect handling matter for credential isolation. Compose and the benchmark stack need coordinated configuration. Browser OpenAPI access needs a documented authorization path.
