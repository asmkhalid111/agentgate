package com.agentgate.gateway.identity;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;

/**
 * Converts Keycloak OAuth2 JWT tokens into an AgentAuthenticationToken with parsed AgentIdentity.
 */
public class JwtAgentAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String clientId = extractClientId(jwt);
        Set<String> scopes = extractScopes(jwt);
        Set<String> roles = extractRoles(jwt);

        AgentIdentity identity = new AgentIdentity(clientId, scopes, roles);

        Set<GrantedAuthority> authorities = new HashSet<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            if ("tool:admin".equalsIgnoreCase(scope) || "admin".equalsIgnoreCase(scope)) {
                roles.add("ADMIN");
            }
        }
        for (String role : roles) {
            String roleName = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            authorities.add(new SimpleGrantedAuthority(roleName));
        }

        return new AgentAuthenticationToken(identity, jwt, authorities);
    }

    private String extractClientId(Jwt jwt) {
        if (jwt.hasClaim("azp")) {
            return jwt.getClaimAsString("azp");
        }
        if (jwt.hasClaim("client_id")) {
            return jwt.getClaimAsString("client_id");
        }
        return jwt.getSubject();
    }

    private Set<String> extractScopes(Jwt jwt) {
        Set<String> scopes = new HashSet<>();
        if (jwt.hasClaim("scope")) {
            String scopeClaim = jwt.getClaimAsString("scope");
            if (scopeClaim != null) {
                scopes.addAll(Arrays.asList(scopeClaim.split("\\s+")));
            }
        }
        if (jwt.hasClaim("scp")) {
            List<String> scpList = jwt.getClaimAsStringList("scp");
            if (scpList != null) {
                scopes.addAll(scpList);
            }
        }
        return scopes;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        if (jwt.hasClaim("realm_access")) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof Collection<?>) {
                    for (Object r : (Collection<?>) rolesObj) {
                        if (r != null) {
                            roles.add(r.toString());
                        }
                    }
                }
            }
        }
        if (jwt.hasClaim("roles")) {
            List<String> rolesList = jwt.getClaimAsStringList("roles");
            if (rolesList != null) {
                roles.addAll(rolesList);
            }
        }
        return roles;
    }
}

