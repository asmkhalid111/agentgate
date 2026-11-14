# Antigravity skills and prompts

`AGENTS.md` is an always-active workspace rule. **This `SKILLS.md` file is an index only**; Antigravity's actual discovered skills are `.agents/skills/<name>/SKILL.md` and have YAML frontmatter.

Current skills:
- `milestone-planning`: create scope-bound plans; stop before coding.
- `milestone-completion`: verify and create evidence-backed completion reports.
- `debugging`: reproduce, isolate, fix and log real defects.

Reusable operator prompts in `/prompts/`:
- `00-MASTER-INITIALIZATION.md` — first paste in Antigravity, M00 planning only.
- `01-PLAN-MILESTONE.md` — later milestone plans.
- `02-IMPLEMENT-APPROVED.md` — implement only an approved plan.
- `03-REMEDIATION.md` — correct a rejected plan or completion.
- `04-INSTRUCTOR-CHANGE.md` — handle real checkpoint request.
- `05-REVIEW-SUMMARY.md` — request reviewer-ready artifact bundle.

Check Antigravity Customizations panel for discovered files; do not assume a standalone `skills.md` is auto-loaded.
