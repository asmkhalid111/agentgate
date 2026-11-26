# Delegated order lookup and live policy changes

Baseline 688a54f. Reviewer sign-off pending.

The local finance agent obtained an agent token and a separate user-consented assertion via the Keycloak authorization-code flow with PKCE. The gateway decoded the assertion on the current MCP request and checked its authorized client, audience, scope, expiration and subject. The gateway used a PostgreSQL order-to-subject mapping for delegated orders_get policy evaluation. The governance audit used the verified subject on both outcomes.

Run python scripts/verify_delegated_orders.py from agentgate with the updated local Compose stack. The script adds two unique demo ownership fixtures and one scoped policy and cleans them up. The mock order response uses synthetic business data. The gateway table is the authority for this demonstration.

## Verification

- Maven package succeeded with all 302 existing gateway tests. Both additional DelegationFilterTest cases passed separately, covering unsigned identity, expiry and client mismatch.
- Agent-only discovery succeeded. The own-order call produced SUCCESS with pol-read-orders. The cross-customer call produced BLOCKED with DEFAULT_DENY. Both audit rows contained the same verified human subject and finance agent ID.
- Policy version 1 denied the own-order lookup within 0.12 seconds. Version 2 enabled dry-run within 0.10 seconds, allowed the lookup and logged the would-be denial.
