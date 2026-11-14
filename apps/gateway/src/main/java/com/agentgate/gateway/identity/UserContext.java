package com.agentgate.gateway.identity;

import java.util.Collections;
import java.util.Set;

/**
 * Represents the verified end-user delegation context asserted via a cryptographically signed user JWT.
 * The assertion must be explicitly bound to the calling agent via the 'azp' (authorized party) claim.
 */
public record UserContext(
        String userId,
        String authorizedParty,
        Set<String> delegationScopes
) {
    public UserContext {
        delegationScopes = delegationScopes != null ? Collections.unmodifiableSet(delegationScopes) : Collections.emptySet();
    }

    public boolean isAuthorizedForAgent(String agentClientId) {
        return authorizedParty != null && authorizedParty.equals(agentClientId);
    }
}

