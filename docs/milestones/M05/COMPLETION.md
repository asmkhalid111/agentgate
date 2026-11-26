# C3 and C4 follow-up

Reviewer sign-off pending. Baseline commit 99622c02c3970ffc670ebddfe4d41ae7d904357d matched local HEAD and origin master.

- C3 gateway changes cover administrative access to OpenAPI and management endpoints, Bean Validation, RFC 9457 API errors, and bounded pagination for registry, permissions, and policy lists. Pages use zero-based page and size parameters and retain the JSON array response. Pages are sliced from existing collections. Probe routes remain reachable for Compose. OpenAPI access was verified with a bearer token. The demo service retains its existing access model.
- C4 adds JSON logging and micrometer trace propagation on the REST and downstream MCP paths. The existing Compose stack started with all services healthy. [Live evidence](C4_LIVE_EVIDENCE.txt) records health and readiness on both apps, protected OpenAPI and metrics, and matching trace IDs in gateway and demo-tools logs. The temporary read-only demo policy was deleted.
- Tests cover API access, Problem Details, page bounds, readiness, metrics, and downstream MCP behavior. The existing reserved-name test now uses the typed registry request.
- Local raw Maven output is in target-c3-c4-verification.log, target-c3-c4-package.log, and target-c3-c4-final.log. Per-test reports are in apps/gateway/target/surefire-reports.

## Observed verification failures

- The first baseline run had 40 failures with Redis unavailable. Using the existing Compose Redis service yielded 300 passing tests at the same commit.
- A clean build caught a test still passing a map to the typed registry method. The caller was updated and its reserved-name assertion was preserved.
- The first live JSON logs lacked trace IDs. Adding the Brave runtime bridge alongside Spring Boot tracing configuration in both apps corrected the issue. The successful live run used the repository authorization-code helper and supplied the required dry-run field on the temporary policy.

## Final Maven result

The final Maven test run completed successfully for both modules. Gateway ran 302 tests with zero failures, errors, or skips, including both FR1 downstream MCP tests. The local checkpoint contains the C3/C4 application changes, regression tests, requirements updates, and live trace evidence.
