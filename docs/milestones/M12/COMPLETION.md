# Hosted CI checkpoint

Review branch: review/agentgate-ci.

The first hosted run at 6daee49 exposed an order-dependent test fixture. ToolRegistryServiceTest granted the finance agent access to inventory_check in the shared H2 database. The downstream integration test subsequently expected that access to be absent. Commit 010908b gives the downstream test its own database. Seven targeted tests passed with the registry suite scheduled first. Raw local output is in target-hosted-ci-isolation.log.

- [First hosted run](https://github.com/asmkhalid111/agentgate/actions/runs/45945418567)
- [Follow-up hosted run](https://github.com/asmkhalid111/agentgate/actions/runs/45946010044)

## Resume point

Hosted workflow execution and artifact publication satisfy the C5 CI checkpoint. Continue from the final hosted result below. QA2 performance acceptance remains open.

The follow-up run passed Maven, application startup, protected demo APIs, delegated audit lookup, and automated adoption. Artifact 15174364443 contains fresh reports. QA1 classified two early HTTP 401 rejections as failures because the cases expected a later HTTP 200 policy denial. The next revision updates both checked-in cases and their generator to require the early authentication rejection. The hosted k6 run completed 17,069 governed calls, dropped 932 iterations, and measured 548.15 ms p95. QA2 remains below the course acceptance target for throughput and above it for latency.

## Reruns after the first complete workflow

- [Run 45947271528](https://github.com/asmkhalid111/agentgate/actions/runs/45947271528) stopped on an order-dependent webhook assertion. Commit b0d6d3a checks the specific approval ID and confirms rollback removed its row. All four targeted tests passed.
- [Run 45947735771](https://github.com/asmkhalid111/agentgate/actions/runs/45947735771) stopped on shared kill-switch state affecting rate-limit integration. Commit cd4370b gives test contexts separate H2 databases and isolates the MCP resilience discovery context. All 27 targeted tests passed with the discovery suite before rate limiting.
- [Run 45948213771](https://github.com/asmkhalid111/agentgate/actions/runs/45948213771) found that the kill-switch controller suite also shared a Spring context with approval and discovery tests. Commit 285298d gives the controller suite its own database. The 48 targeted tests passed with the controller suite before its consumers.
- [Run 45948692935](https://github.com/asmkhalid111/agentgate/actions/runs/45948692935) found audit tests racing the production asynchronous listener and a timeout assertion dependent on JDK exception text. Commit 3c5fad4 makes the audit fixture drive one execution and checks timeout classification through the audit outcome. Twenty targeted tests passed.
- [Run 45949032689](https://github.com/asmkhalid111/agentgate/actions/runs/45949032689) completed every verification stage and uploaded fresh results.

## Final hosted result

The [verified run](https://github.com/asmkhalid111/agentgate/actions/runs/45949032689) built the applications from a clean checkout. Its [verification artifact](https://github.com/asmkhalid111/agentgate/actions/runs/45949032689/artifacts/15176149154) contains 308 passing Maven tests with zero failures, errors, and skips, including the PostgreSQL/Redis integration and QA4 outage tests. The hosted QA1 suite blocked or held 38 of 38 malicious cases and falsely blocked zero of 200 legitimate cases. The protected demo API check passed. QA3 found both audited outcomes in 0.051 seconds. The automated QA5 rehearsal took 0.36 seconds.

The k6 step executed and uploaded its report. Over 180 seconds at an offered rate of 100 calls per second it completed 17,127 valid calls, dropped 873 iterations, and measured 423.45 ms p95 gateway-added overhead. QA2 crossed the latency and delivery thresholds. That threshold failure accounts for the workflow's red conclusion.

The review branch contains the tested revision 3c5fad4. The local evidence links preserve the hosted revision and run ID for resuming. The independent QA5 trial remains pending.
