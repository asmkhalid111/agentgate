# Review and Approval Contract

## Plan review checklist
- Each planned feature maps to explicit FR/QA/C IDs; out-of-scope items rejected.
- Existing modules and committed changes inspected before the plan.
- Files to create/modify enumerated, no duplicate sources of truth, no milestone folders in `src`.
- Trust boundaries and denial paths described; security applied to MCP list AND call.
- New dependencies and complex designs justified with an alternative and minimal option.
- Test list includes positive/negative/boundary/concurrency as applicable, with exact commands and expected artifacts.
- Migration, rollback/recovery and config effect considered.
- Anticipated risks, exit gate and user-observable demo defined.

Verdict: APPROVED | REVISE PLAN | BLOCKED. No code until APPROVED.

## Completion review checklist
- Requirement statuses match tests; unknown/failing claims not marked verified.
- Build/test output, CI link or local logs, and evidence paths provided.
- Git diff and meaningful incremental commits enumerated.
- Security boundary tested, configuration/secrets checked, errors logged and not swept away.
- Architecture docs and handoff memory updated; no unrelated scope creep.
- Exit gate is demonstrated, not described only.

Verdict: APPROVED | CHANGES REQUIRED | BLOCKED. The next milestone cannot begin without APPROVED.
