package com.agentgate.gateway.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

public final class DelegationFilter extends OncePerRequestFilter {
    public static final String SUBJECT = DelegationFilter.class.getName() + ".subject";
    private final JwtDecoder decoder;

    public DelegationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var assertions = Collections.list(request.getHeaders("X-User-Assertion"));
        if (request.getHeader("X-User-Id") != null) {
            response.sendError(401);
            return;
        }
        if (!assertions.isEmpty()) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (assertions.size() != 1 || !(auth instanceof AgentAuthenticationToken agent)
                    || !agent.getAgentIdentity().scopes().contains("delegation:act_as_user")) {
                response.sendError(401);
                return;
            }
            try {
                Jwt jwt = decoder.decode(assertions.getFirst());
                String scope = jwt.getClaimAsString("scope");
                if (jwt.getSubject() == null || jwt.getSubject().isBlank()
                        || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())
                        || jwt.getAudience() == null || !jwt.getAudience().contains("agentgate-gateway")
                        || !agent.getAgentIdentity().clientId().equals(jwt.getClaimAsString("azp"))
                        || scope == null || !Arrays.asList(scope.split(" +")).contains("tool:delegate")) {
                    response.sendError(401);
                    return;
                }
                request.setAttribute(SUBJECT, jwt.getSubject());
            } catch (JwtException | IllegalArgumentException ex) {
                response.sendError(401);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/mcp") || path.startsWith("/mcp/"));
    }
}
