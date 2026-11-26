# C5 testing and CI checkpoint

The repository now has a GitHub Actions workflow that runs Maven verification and the QA1, QA2, QA3, and QA5 scripts. Maven verification also includes the deterministic QA4 outage check and the QA3 tamper tests. The workflow retains test reports and runtime evidence. QA5 automation rehearses the tool onboarding steps. The timed independent developer trial is still pending.

## Local verification

- Maven verify passed with 304 gateway tests, one storage integration test, and two demo-tools tests. The storage test starts disposable PostgreSQL and Redis containers, applies the real Flyway migrations, reads the migrated registry, and checks Redis counters. The demo module runs its unit and MVC slice tests with JUnit 5. The gateway retains Spring Boot's managed JUnit 6 version because Spring's test extension uses an API absent in JUnit 5.
- The authenticated QA3 audit lookup found both delegated order decisions in 0.076 seconds. The QA5 automated rehearsal passed in 8.40 seconds and cleaned up its temporary registration and policy.
- The k6 workload ran for 180 seconds at an offered rate of 100 calls per second. It recorded 17,172 valid calls, 829 dropped iterations, and 429.77 ms p95 gateway overhead. The QA2 thresholds failed. The earlier full-rate benchmark remains a separate acceptance result.
- The hosted GitHub Actions run is pending. C5 remains partially verified. The five local Compose services were healthy after the benchmark stack was stopped.

## Failed runs and recovery

An initial test JVM ran out of native memory while the demo stack was running. The successful verification used a separate Maven integration phase for Testcontainers, capped processor use, and paused the demo services other than Redis during Maven execution. An existing timeout test also failed when its exception had no text. The assertion now checks typed timeout and cancellation causes. The first k6 attempt exhausted local resources. The second run capped k6 at 48 concurrent workers while retaining the offered rate and acceptance thresholds.

Raw local build and benchmark output is in target-c5-final.log and target-c5-k6-final.log. The accompanying metrics were exported by k6.
