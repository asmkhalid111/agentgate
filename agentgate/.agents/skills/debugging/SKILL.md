---
name: debugging
description: Reproduce, isolate and fix an observed AgentGate error with regression evidence, logging real mistakes for course process assessment. Use for build, security, MCP, schema, policy and concurrency bugs.
---
# Defect protocol

1. Preserve failing command, complete error output and commit SHA.
2. Minimize to a deterministic reproduction, identify actual root cause.
3. Write/strengthen a regression test first where practical.
4. Implement the smallest fix permitted by approved milestone scope; request plan revision for design changes.
5. Rerun focused and broader relevant tests. Keep both failed and passing evidence.
6. Add an *observed* `ERRORS.md` row; when AI output was at fault, link a factual AI_USAGE_LOG correction. Anticipated risks never count as mistakes.
