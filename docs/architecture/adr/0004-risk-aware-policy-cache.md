# ADR 0004 Bound policy fallback by risk and elapsed time

## Context
FR3 requires policy updates within ten seconds. QA4 requires stricter behavior for medium and high risk during a policy-store outage.

## Options
Query storage on each invocation, use the last policy snapshot for all risks, deny every call during an outage, or apply a time-limited fallback for low risk.

## Decision
Refresh the active policy snapshot every five seconds and immediately after an administrative update. When refresh fails, deny medium- and high-risk calls and allow existing matching low-risk policy only while the last good snapshot is younger than five minutes. Other governance checks continue to apply.

## Consequences
Most evaluations avoid a live policy query. A fresh snapshot may briefly lag a database update, and low-risk fallback carries an explicit policy freshness cost. Shared database failure can also disable other dependencies.

## Measured evidence
Live policy versions became effective in 0.12 and 0.10 seconds on the demo workflow. The injected outage test allowed a matching low-risk call at 299 seconds, blocked it at 300 and 301 seconds, and blocked medium and high risk without dispatch. [Live policy changes](../../milestones/M06/COMPLETION.md) · [Outage evidence](../../quality/QA4_POLICY_STORE_OUTAGE_VERIFICATION.md)
