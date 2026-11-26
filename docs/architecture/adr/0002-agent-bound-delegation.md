# ADR 0002 Bind user delegation to the registered agent

## Context
An agent may act on behalf of a user. A customer identifier supplied as an argument does not establish identity or order ownership.

## Options
Trust a caller-supplied header, accept any valid user token issued by the realm, or require a separate signed assertion bound to the invoking agent.

## Decision
Use a user-consented Keycloak authorization-code flow with PKCE. Validate the assertion through the configured JWT decoder and check its expiry, gateway audience, delegation scope and authorized party. Derive the user identity from its subject and use server-controlled ownership for the demo order lookup.

## Consequences
Agent-only calls retain their service credentials. Delegated calls require user consent and a separate assertion. Ownership data must come from a trusted source and must track the protected resource.

## Measured evidence
Two live outcomes recorded the same agent and verified end-user subject. The own-order lookup succeeded and a cross-customer lookup was blocked. Two additional tests covered unsigned identity, expired assertions and an assertion issued to a different client. [Delegation checkpoint](../../milestones/M06/COMPLETION.md) · [Audit lookup evidence](../../quality/QA3_AUDITABILITY.md)
