package com.agentgate.gateway.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom Authentication token representing an authenticated AI Agent in Spring Security.
 */
public class AgentAuthenticationToken extends AbstractAuthenticationToken {

    private final AgentIdentity agentIdentity;
    private final Object credentials;

    public AgentAuthenticationToken(AgentIdentity agentIdentity, Object credentials, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.agentIdentity = agentIdentity;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public AgentIdentity getPrincipal() {
        return agentIdentity;
    }

    public AgentIdentity getAgentIdentity() {
        return agentIdentity;
    }
}

