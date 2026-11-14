package com.agentgate.gateway.identity;

import java.util.Collections;
import java.util.Set;

/**
 * Represents the verified machine identity of an AI Agent authenticated via OAuth2 Client Credentials.
 */
public record AgentIdentity(
        String clientId,
        Set<String> scopes,
        Set<String> roles
) {
    public AgentIdentity {
        scopes = scopes != null ? Collections.unmodifiableSet(scopes) : Collections.emptySet();
        roles = roles != null ? Collections.unmodifiableSet(roles) : Collections.emptySet();
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}

