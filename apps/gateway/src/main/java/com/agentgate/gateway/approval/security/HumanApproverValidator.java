package com.agentgate.gateway.approval.security;

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
 * Validates that an incoming authentication belongs to a genuine human approver,
 * explicitly denying automated agent service-account tokens (Reviewer Correction A).
 */
@Component
public class HumanApproverValidator {

    private static final Logger log = LoggerFactory.getLogger(HumanApproverValidator.class);

    private static final Set<String> KNOWN_AGENT_CLIENTS = Set.of(
            "agent-finance-bot",
            "agent-support-bot",
            "agent-unauthorized-bot"
    );

    /**
     * Verifies that the caller is a genuine human user with ROLE_APPROVER.
     *
     * @param authentication active Spring Security authentication
     * @return verified human username (to be recorded as decidedBy)
     * @throws AccessDeniedException if caller lacks authority or is an automated agent service account
     */
    public String verifyHumanApprover(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required for human approval decisions");
        }

        // 1. Must possess ROLE_APPROVER authority
        boolean hasApproverRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_APPROVER"));

        if (!hasApproverRole) {
            log.warn("Access denied to approval API: caller '{}' lacks ROLE_APPROVER", authentication.getName());
            throw new AccessDeniedException("Caller lacks required ROLE_APPROVER authority");
        }

        // 2. Extract underlying claims (JWT or OIDC user attributes)
        Map<String, Object> claims = extractClaims(authentication);
        if (claims == null) {
            // Fail closed if claims cannot be inspected
            log.warn("Access denied to approval API: no verifiable credentials found in authentication");
            throw new AccessDeniedException("No verifiable credentials found for human approver");
        }

        // 3. Inspect machine / service-account indicators (Reviewer Correction A)
        String preferredUsername = getClaimString(claims, "preferred_username");
        String subject = getClaimString(claims, "sub");
        String azp = getClaimString(claims, "azp");
        String clientId = getClaimString(claims, "client_id");
        String grantType = getClaimString(claims, "gty");

        // Indicator A: Keycloak service account username convention (e.g. "service-account-agent-finance-bot")
        if (preferredUsername != null && preferredUsername.startsWith("service-account-")) {
            log.warn("Security violation: Agent service account '{}' attempted human approval", preferredUsername);
            throw new AccessDeniedException("Agent service accounts are not permitted to execute human approval decisions");
        }

        // Indicator B: Subject matches service account pattern
        if (subject != null && subject.startsWith("service-account-")) {
            log.warn("Security violation: Machine subject '{}' attempted human approval", subject);
            throw new AccessDeniedException("Machine service accounts are not permitted to execute human approval decisions");
        }

        // Indicator C: Client-credentials grant type explicit claim
        if ("client-credentials".equalsIgnoreCase(grantType)) {
            log.warn("Security violation: Client credentials token attempted human approval");
            throw new AccessDeniedException("Client credentials tokens cannot perform human approval decisions");
        }

        // Indicator D: Token azp or clientId is a known agent client, and username matches the agent or is absent
        String effectiveClient = azp != null ? azp : clientId;
        if (effectiveClient != null && (effectiveClient.startsWith("agent-") || KNOWN_AGENT_CLIENTS.contains(effectiveClient))) {
            if (preferredUsername == null || preferredUsername.equals(effectiveClient) || preferredUsername.startsWith("service-account-")) {
                log.warn("Security violation: Agent client '{}' attempted human approval without human user context", effectiveClient);
                throw new AccessDeniedException("Agent client tokens cannot perform human approval decisions");
            }
        }

        // 4. Must have a reliable human identifier (preferred_username or email)
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

        if (humanUsername == null || humanUsername.isBlank()) {
            log.warn("Fail-closed: Unable to determine verified human approver identity from token");
            throw new AccessDeniedException("Unable to reliably verify human approver identity from token");
        }

        return humanUsername;
    }

    private String getClaimString(Map<String, Object> claims, String key) {
        Object val = claims.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private Map<String, Object> extractClaims(Authentication authentication) {
        if (authentication instanceof AgentAuthenticationToken agentToken) {
            if (agentToken.getCredentials() instanceof Jwt jwt) {
                return jwt.getClaims();
            }
        }
        if (authentication.getCredentials() instanceof Jwt jwt) {
            return jwt.getClaims();
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
}
