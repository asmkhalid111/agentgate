---
name: milestone-completion
description: Verify implemented AgentGate milestone against the approved plan and produce an evidence-backed completion report, requirement updates and error log. Use at the end of an implementation milestone.
---
# Completion verification procedure

1. Diff Git against approved baseline, list all changed files and commits.
2. Run commands from approved plan; capture output files and any failures. Do not invent execution.
3. Confirm all security/trust boundaries and negative tests for scope.
4. Update matrix rows only when evidence supports correct status; link raw evidence.
5. Update `COMPLETION.md`, `ERRORS.md`, `AI_USAGE_LOG.md`, relevant ADRs and `MEMORY.md`.
6. State remaining risk and reviewer-required decisions. Stop awaiting sign-off.
