---
name: milestone-planning
description: Create a reviewer-gated, requirement-traceable AgentGate milestone plan before any application implementation. Use when asked to plan M00-M10 or a checkpoint change.
---
# Milestone planning procedure

1. Read root `AGENTS.md`, `docs/governance/REQUIREMENTS_MATRIX.md`, `ROADMAP.md`, `MEMORY.md`, `ERRORS.md`, relevant ADRs and prior completion.
2. Inspect actual repository status and paths; state what is proven vs unverified.
3. Map every planned change to FR, QA, or C requirement IDs.
4. Prefer the least complex valid implementation. For material choices, include one rejected alternative and reason.
5. Specify exact files, migration/config changes, security boundaries, focused tests, failure cases, expected evidence paths and rollback.
6. Produce `docs/milestones/Mxx/PLAN.md`; mark DRAFT. Request reviewer approval; do not code.
