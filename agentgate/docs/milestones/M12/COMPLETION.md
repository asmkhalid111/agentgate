# Hosted CI checkpoint

Review branch: review/agentgate-ci.

The first hosted run at 6daee49 exposed an order-dependent test fixture. ToolRegistryServiceTest granted the finance agent access to inventory_check in the shared H2 database. The downstream integration test subsequently expected that access to be absent. Commit 010908b gives the downstream test its own database. Seven targeted tests passed with the registry suite scheduled first. Raw local output is in target-hosted-ci-isolation.log.

- [First hosted run](https://github.com/asmkhalid111/agentgate/actions/runs/45945418567)
- [Follow-up hosted run](https://github.com/asmkhalid111/agentgate/actions/runs/45946010044)

## Resume point

Check the follow-up run's step outcomes and uploaded artifact before updating C5. If Maven or a live test fails, inspect its logs and keep fixes on the review branch. Keep QA2 acceptance distinct from successful execution of the load check.

The follow-up run passed Maven, application startup, protected demo APIs, delegated audit lookup, and automated adoption. Artifact 15174364443 contains fresh reports. QA1 classified two early HTTP 401 rejections as failures because the cases expected a later HTTP 200 policy denial. The next revision updates both checked-in cases and their generator to require the early authentication rejection. The hosted k6 run completed 17,069 governed calls, dropped 932 iterations, and measured 548.15 ms p95. QA2 remains below the course acceptance target for throughput and above it for latency.
