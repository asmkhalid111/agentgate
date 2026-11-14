package com.agentgate.gateway.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Utility to access the currently authenticated AgentIdentity from the SecurityContextHolder.
 */
public final class AgentContextHolder {

    private AgentContextHolder() {}

    public static Optional<AgentIdentity> getCurrentAgent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AgentAuthenticationToken agentToken) {
            return Optional.of(agentToken.getAgentIdentity());
        }
        return Optional.empty();
    }
}

