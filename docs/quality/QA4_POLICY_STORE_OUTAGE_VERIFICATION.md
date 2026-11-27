# AgentGate — QA4 Policy-Store Outage Verification Report (S1-01)

**Document Reference:** `docs/quality/QA4_POLICY_STORE_OUTAGE_VERIFICATION.md`  
**Milestone:** M04 Submission Completion — Task S1-01  
**Date:** 2026-11-25  
**Status:** **PARTIAL (Evaluator Fallback Verified via Integration Test; Shared-DB Full-Outage Limitation Documented)**

---

## 1. Exact Teacher QA4 Requirement

From [SOURCE_REQUIREMENTS.md](file:///e:/Codes/Software-Architecture-Project/agentgate/docs/governance/SOURCE_REQUIREMENTS.md#L28):
> **QA4 Fail-safe availability.** If the policy store is unreachable, medium- and high-risk tools fail closed, while low-risk tools keep working on the last known policies for up to 5 minutes.

---

## 2. Test Methodology & Failure Injection Technique

- **Test Class:** [PolicyStoreOutageIntegrationTest.java](file:///e:/Codes/Software-Architecture-Project/agentgate/apps/gateway/src/test/java/com/agentgate/gateway/policy/PolicyStoreOutageIntegrationTest.java)
- **Environment & Database:** `@SpringBootTest` (`@ActiveProfiles("test")`) backed by H2 in-memory JDBC database (`jdbc:h2:mem:agentgate_test`) using real `JdbcClient` repositories (`RegisteredToolRepository`, `KillSwitchRepository`, `AuditRepository`, `PolicyRepository`).
- **Time Control:** Minimal constructor-injected `java.time.Clock` (`ObjectProvider<Clock>` defaulting to `Clock.systemUTC()` in production) added to [StructuredPolicyEvaluator.java](file:///e:/Codes/Software-Architecture-Project/agentgate/apps/gateway/src/main/java/com/agentgate/gateway/policy/service/StructuredPolicyEvaluator.java). A test `@Bean MutableClock` controls elapsed outage time deterministically without `Thread.sleep`.
- **Failure Injection:** `@MockitoSpyBean PolicyRepository` throws `DataAccessResourceFailureException("Simulated policy store unreachable")` on `findAll()`, while `evaluator.reloadPolicies()` is invoked directly—exercising the real cache-retention and fail-safe logic rather than `setSimulatedDbAvailability()`.
- **Actual Components Executed:** `ToolExecutionPipeline`, `StructuredPolicyEvaluator`, `RegisteredToolRepository`, `KillSwitchService`, `KillSwitchRepository`, `AuditRecorder`, `JdbcAuditRepository`, `AuditVerificationService`, and `@MockitoSpyBean RestToolAdapter` (simulating downstream HTTP responses).

---

## 3. Observed Scenario Outcomes

| Phase | Scenario | Expected Behavior | Observed Behavior | Audit Sequence / Outcome |
|---|---|---|---|---|
| **A. Healthy Baseline** | Low (`qa4_low_read`), Medium (`qa4_medium_write`), High (`qa4_high_refund`) | All 3 permitted; downstream invoked 3 times | All 3 succeeded (`verify(restToolAdapter, times(3))`) | Sequences 1–4 (`SUCCESS` + `WRITE_DISPATCH_INTENT`) |
| **B. Policy-Store Outage** | `policyRepository.findAll()` throws `DataAccessResourceFailureException`; `reloadPolicies()` called | `dbAvailable = false`, cached rules retained | Logged error; retained cached rules | — |
| **C. Med/High Fail Closed** | `qa4_medium_write` (`MEDIUM`) and `qa4_high_refund` (`HIGH`) at `T0 + 30s` | Both denied immediately; downstream never called | Denied (`QA4_FAIL_CLOSED`: `"medium/high risk calls fail closed"`); `0` downstream calls | Sequences 5–6 (`GOVERNANCE_DENIAL` / `BLOCKED`) |
| **D. Low-Risk `< 5m`** | Matched `qa4_low_read` (`T0 + 30s`), unmatched `qa4_low_unmatched`, and kill-switched `qa4_low_read` | Matched allowed; unmatched default-denied; kill switch blocks | Matched succeeded (`1` downstream call); unmatched denied (`Default Deny`); kill switch blocked (`KILL_SWITCH_ACTIVE`) | Sequences 7–10 (`SUCCESS` and `BLOCKED`) |
| **E. Exact 5m Boundary** | `T0 + 299s` (`4m59s`), `T0 + 300s` (`5m00s`), `T0 + 301s` (`5m01s`) | `299s` allowed; `300s` & `301s` denied (`QA4_CUTOFF`) | `299s` succeeded (`outage duration: 299s`); `300s` and `301s` denied (`QA4_CUTOFF`: `"low-risk fallback window expired"`); `0` downstream calls at `>= 300s` | Sequences 11–13 (`SUCCESS` at 299s; `BLOCKED` at 300s, 301s) |
| **F. Recovery** | Repository restored; `pol-qa4-low` updated to `DENY` (v2); `reloadPolicies()` called at `T0 + 310s` | `qa4_low_read` denied by new rule; `qa4_medium_write` succeeds again | `qa4_low_read` denied (`QA4 Revoked Low Read`); `qa4_medium_write` succeeded | Sequences 14–16 (`BLOCKED` then `WRITE_DISPATCH_INTENT` + `SUCCESS`) |
| **G. Audit Integrity** | `auditVerificationService.verifyIntegrity()` | All 16 events intact, valid SHA-256 chain | `valid = true`, `recordsChecked = 16`, `corruptedSequence = null` | 16/16 verified |

---

## 4. Shared-Database Limitation & Scope Distinction

This verification must **not** be presented as equivalent to a live PostgreSQL container shutdown:

1. **Injected `PolicyRepository` Exception (`Verified`):** Demonstrates that `StructuredPolicyEvaluator` and `ToolExecutionPipeline` enforce QA4 fallback, 5-minute cutoff, kill switches, and synchronous auditing when the policy store/query fails while the audit ledger, tool registry, and kill-switch store remain reachable.
2. **Full Shared PostgreSQL Outage (`Fails Closed — Not Low-Risk Available`):** In the deployed `compose.yaml` architecture, `policies`, `registered_tools`, `tool_agent_permissions`, `kill_switches`, and `audit_log` all share a single PostgreSQL instance. If PostgreSQL itself is stopped:
   - `RegisteredToolRepository.findByName()` and `isAgentPermitted()` fail with `DataAccessException` before policy evaluation.
   - `KillSwitchService.checkBlocked()` catches the DB exception and intentionally fails closed (`KILL_SWITCH_UNAVAILABLE`, [KillSwitchService.java#L77-L81](file:///e:/Codes/Software-Architecture-Project/agentgate/apps/gateway/src/main/java/com/agentgate/gateway/killswitch/service/KillSwitchService.java#L77-L81)).
   - `AuditRecorder` fails closed because FR7 mandates synchronous durable audit persistence and no asynchronous/local WAL buffer is enabled.

---

## 5. Real-PostgreSQL Testcontainers Verification (`PolicyStoreOutagePostgresIT`)

Added [PolicyStoreOutagePostgresIT.java](file:///e:/Codes/Software-Architecture-Project/agentgate/apps/gateway/src/test/java/com/agentgate/gateway/policy/PolicyStoreOutagePostgresIT.java) using a disposable `postgres:16-alpine` Testcontainer migrated by Flyway and the unmocked `PolicyRepository`, `RegisteredToolRepository`, `KillSwitchService`, `AuditRepository`, and `AuditVerificationService`.

- **What failed:** `ALTER TABLE policies RENAME TO policies_outage` caused real `PolicyRepository.findAll()` JDBC queries in `StructuredPolicyEvaluator.reloadPolicies()` to fail against PostgreSQL.
- **What stayed reachable:** `registered_tools`, `tool_agent_permissions`, `kill_switches`, and `audit_log` in PostgreSQL remained reachable and enforced throughout the outage.
- **Verified behavior:** Medium- and high-risk calls failed closed before dispatch; matching low-risk calls used the cached policy at `T0 + 30s` and `T0 + 299s` while kill-switch activation still blocked them; low-risk calls failed closed at `T0 + 300s` and `T0 + 301s`; recovery (`ALTER TABLE policies_outage RENAME TO policies`) restored policy reloads and `auditVerificationService.verifyIntegrity()` verified all `16` PostgreSQL audit records.
- **Command & result:** `.\mvnw.cmd -pl apps/gateway "-Dtest=NonExistentTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=PolicyStoreOutagePostgresIT" verify` -> `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`, 32.60 s).
- **Handout alignment:** Satisfies the QA4 policy-store-unreachable scenario while keeping the shared-PostgreSQL full-outage limitation above explicit (`PARTIAL`).
