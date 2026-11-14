# Implement an APPROVED milestone

The architecture reviewer has approved **{{MILESTONE_ID}}** plan **{{PLAN_PATH}}**, with these exact conditions: **{{APPROVAL_CONDITIONS}}**. The repository baseline is commit **{{COMMIT_SHA}}**.

Read `AGENTS.md`, the complete approved plan, requirement matrix, memory and relevant errors. Implement ONLY approved steps in order. Use feature packages, not milestone code directories. Tests are part of implementation; run focused tests then relevant integration/architecture tests and CI-equivalent commands.

If a dependency/API incompatibility or fundamental design obstacle arises, pause and request replanning; do not quietly substitute a different framework or disable security. Do not hardcode secrets or use paid services. Keep incremental meaningful commits. Capture **actual** stdout/stderr and failed attempts, not summarized invented passing results. Record real defects in ERRORS, significant AI corrections in AI_USAGE_LOG.

Update `docs/milestones/{{MILESTONE_ID}}/COMPLETION.md` with exact files changed, commits, commands/results, raw evidence paths, requirement statuses, unresolved issues and architecture artifacts updated. Update MEMORY with factual stage, but mark milestone *AWAITING REVIEW*, not APPROVED. Stop, and return a concise reviewer-ready summary. Do not begin {{NEXT_MILESTONE}}.
