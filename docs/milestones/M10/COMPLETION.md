# Environment configuration checkpoint

C1 startup was accepted by the project owner after the source-build checkpoint. The physical 8 GB host limitation remains documented in M09.

C2 development credentials now come from the environment or an ignored local file. Compose requires the runtime values. Keycloak imports placeholders and the verification clients read the same values. The initializer generates nine random values, uses owner-only permissions where supported, and preserves an existing file. GitHub Actions uses the initializer in its disposable workspace. Existing local database and realm credentials were preserved to keep the persistent demo usable.

## Evidence

- Python scripts compiled and Compose resolved its configuration. The initialization check generated nine distinct values and kept the file byte-for-byte on a second run.
- A separate Compose project used freshly generated credentials and fresh database volumes. The source build succeeded and all five services became healthy. The QA5 automation completed in 4.36 seconds. The delegated MCP workflow verified user consent, client and human authentication, policy changes, and the administrator audit lookup in 0.113 seconds. Only the temporary verification project and its volumes were removed.
- A tracked-source search found no matches for the retired demo credential literals or naming patterns. This check covers the current files. Historical commits were preserved. Local logs are in target-c2-fresh.log.

## Resume point

The project owner reported that an independent QA5 participant is unavailable. QA5 and the final C9 evidence remain pending. Continue with the [demo API C3 plan](../M11/PLAN.md), then verify hosted CI when repository publishing is authorized.
