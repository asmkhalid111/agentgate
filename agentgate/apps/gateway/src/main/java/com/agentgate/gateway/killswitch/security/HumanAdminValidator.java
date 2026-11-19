package com.agentgate.gateway.killswitch.security;

import com.agentgate.gateway.identity.AgentAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Validates that an incoming authentication belongs to a genuine human administrator,
 * explicitly denying automated agent service-account tokens (FR8 Security).
 */
@Component
public class HumanAdminValidator {

    private static final Logger log = LoggerFactory.getLogger(HumanAdminValidator.class);

    private static final Set<String> KNOWN_AGENT_CLIENTS = Set.of(
            "agent-finance-bot",
            "agent-support-bot",
            "agent-unauthorized-bot"
    );

    /**
     * Verifies that the caller is a genuine human user with ROLE_ADMIN.
     *
     * @param authentication active Spring Security authentication
     * @return verified human username (to be recorded as createdBy)
     * @throws AccessDeniedException if caller lacks authority or is an automated agent service account
     */
    public String verifyHumanAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required for emergency kill switch administration");
        }

        // 1. Must possess ROLE_ADMIN authority
        boolean hasAdminRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));

        if (!hasAdminRole) {
            log.warn("Access denied to kill switch admin API: caller '{}' lacks ROLE_ADMIN", authentication.getName());
            throw new AccessDeniedException("Caller lacks required ROLE_ADMIN authority");
        }

        // 2. Extract underlying claims (JWT or OIDC user attributes)
        Map<String, Object> claims = extractClaims(authentication);
        if (claims == null) {
            log.warn("Access denied to kill switch admin API: no verifiable credentials found in authentication");
            throw new AccessDeniedException("No verifiable credentials found for human administrator");
        }

        // 3. Inspect machine / service-account indicators
        String preferredUsername = getClaimString(claims, "preferred_username");
        String subject = getClaimString(claims, "sub");
        String azp = getClaimString(claims, "azp");
        String clientId = getClaimString(claims, "client_id");
        String grantType = getClaimString(claims, "gty");

        // Indicator A: Keycloak service account username convention
        if (preferredUsername != null && preferredUsername.startsWith("service-account-")) {
            log.warn("Security violation: Machine service account '{}' attempted kill switch admin operation", preferredUsername);
            throw new AccessDeniedException("Machine service accounts are not permitted to manage emergency kill switches");
        }

        // Indicator B: Subject matches service account pattern
        if (subject != null && subject.startsWith("service-account-")) {
            log.warn("Security violation: Machine subject '{}' attempted kill switch admin operation", subject);
            throw new AccessDeniedException("Machine service accounts are not permitted to manage emergency kill switches");
        }

        // Indicator C: Client-credentials grant type explicit claim
        if ("client-credentials".equalsIgnoreCase(grantType)) {
            log.warn("Security violation: Client credentials token attempted kill switch admin operation");
            throw new AccessDeniedException("Client credentials tokens cannot manage emergency kill switches");
        }

        // Indicator D: Token azp or clientId is an agent client
        String effectiveClient = azp != null ? azp : clientId;
        if (effectiveClient != null && (effectiveClient.startsWith("agent-") || KNOWN_AGENT_CLIENTS.contains(effectiveClient))) {
            if (preferredUsername == null || preferredUsername.equals(effectiveClient) || preferredUsername.startsWith("service-account-")) {
                log.warn("Security violation: Agent client '{}' attempted kill switch admin operation without human user context", effectiveClient);
                throw new AccessDeniedException("Agent client tokens cannot manage emergency kill switches");
            }
        }

        // Indicator E: Possesses agent invocation scope without human identity
        boolean hasAgentScope = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("SCOPE_agent:invoke"));
        if (hasAgentScope && (preferredUsername == null || preferredUsername.startsWith("agent-"))) {
            log.warn("Security violation: Agent invoke scope token attempted kill switch admin operation");
            throw new AccessDeniedException("Machine agent tokens cannot manage emergency kill switches");
        }

        // 4. Derives verified human administrator username
        String humanUsername = preferredUsername;
        if (humanUsername == null || humanUsername.isBlank()) {
            humanUsername = getClaimString(claims, "email");
        }
        if (humanUsername == null || humanUsername.isBlank()) {
            humanUsername = subject;
        }
        if (humanUsername == null || humanUsername.isBlank()) {
            humanUsername = authentication.getName();
        }

        return humanUsername;
    }

    private Map<String, Object> extractClaims(Authentication authentication) {
        if (authentication instanceof AgentAuthenticationToken agentToken) {
            if (agentToken.getCredentials() instanceof Jwt jwt) {
                return jwt.getClaims();
            }
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaims();
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser.getClaims();
        }
        if (authentication.getPrincipal() instanceof OAuth2User oauth2User) {
            return oauth2User.getAttributes();
        }
        return null;
    }

    private String getClaimString(Map<String, Object> claims, String key) {
        Object val = claims.get(key);
        return val != null ? val.toString() : null;
    }
}
