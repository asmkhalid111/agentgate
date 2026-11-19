package com.agentgate.gateway.config;

import com.agentgate.gateway.identity.JwtAgentAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.util.*;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAgentAuthenticationConverter jwtAgentAuthenticationConverter() {
        return new JwtAgentAuthenticationConverter();
    }

    /**
     * Maps Keycloak realm roles (e.g. APPROVER) to Spring Security ROLE_<ROLE>
     * authorities
     * for interactive browser sessions established via oauth2Login().
     */
    @Bean
    public GrantedAuthoritiesMapper userAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
            for (GrantedAuthority authority : authorities) {
                mappedAuthorities.add(authority);
                if (authority instanceof OidcUserAuthority oidcAuth) {
                    extractRolesFromClaims(oidcAuth.getIdToken().getClaims(), mappedAuthorities);
                    if (oidcAuth.getUserInfo() != null) {
                        extractRolesFromClaims(oidcAuth.getUserInfo().getClaims(), mappedAuthorities);
                    }
                } else if (authority instanceof OAuth2UserAuthority oauth2Auth) {
                    extractRolesFromClaims(oauth2Auth.getAttributes(), mappedAuthorities);
                }
            }
            return mappedAuthorities;
        };
    }

    private void extractRolesFromClaims(Map<String, Object> claims, Set<GrantedAuthority> mappedAuthorities) {
        if (claims == null)
            return;
        Object realmAccessObj = claims.get("realm_access");
        if (realmAccessObj instanceof Map<?, ?> realmAccess) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof Collection<?> roles) {
                for (Object role : roles) {
                    if (role instanceof String r && !r.isBlank()) {
                        mappedAuthorities.add(new SimpleGrantedAuthority("ROLE_" + r));
                    }
                }
            }
        }
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String r && !r.isBlank()) {
                    mappedAuthorities.add(new SimpleGrantedAuthority("ROLE_" + r));
                }
            }
        }
    }

    /**
     * Production JWT Decoder with strict fail-fast validation.
     * Throws IllegalStateException on startup if jwk-set-uri is missing.
     * Active for all profiles except 'test'.
     */
    @Bean
    @Profile("!test")
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException(
                    "FATAL: 'spring.security.oauth2.resourceserver.jwt.jwk-set-uri' must not be blank in production/dev. Security requires a live Keycloak IdP.");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        if (issuerUri != null && !issuerUri.isBlank()) {
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
            decoder.setJwtValidator(withIssuer);
        }
        return decoder;
    }

    /**
     * Security filter chain 1: Stateless machine M2M protocols (/mcp, /api/v1/**,
     * Actuator).
     * Authenticated exclusively via OAuth2 Resource Server Bearer JWTs.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtAgentAuthenticationConverter converter)
            throws Exception {
        http
                .securityMatcher("/mcp", "/mcp/**", "/api/v1/**", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**",
                        "/error")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Public observability and documentation endpoints
                        .requestMatchers("/actuator/health", "/actuator/info", "/error", "/swagger-ui/**",
                                "/v3/api-docs/**")
                        .permitAll()
                        // Protected MCP protocol endpoint (strictly requires agent:invoke scope)
                        .requestMatchers("/mcp", "/mcp/**").hasAuthority("SCOPE_agent:invoke")
                        // Protected Human Approval REST API (strictly requires ROLE_APPROVER)
                        .requestMatchers("/api/v1/approvals", "/api/v1/approvals/**").hasAuthority("ROLE_APPROVER")
                        // Policy playground endpoint (accessible to ADMIN and AGENT)
                        .requestMatchers("/api/v1/policies/playground")
                        .hasAnyAuthority("ROLE_ADMIN", "ROLE_AGENT", "SCOPE_tool:admin", "SCOPE_agent:invoke")
                        // Protected administrative management endpoints (FR1 tool registry, FR3
                        // policies)
                        .requestMatchers("/api/v1/registry/**", "/api/v1/policies/**", "/admin/**")
                        .hasAnyAuthority("ROLE_ADMIN", "SCOPE_tool:admin")
                        // Deny all other unmapped routes in this chain
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));

        return http.build();
    }

    /**
     * Security filter chain 2: Stateful Human Approval Web Portal (/approvals/**,
     * /approvals.html).
     * Authenticated via Spring Security standard oauth2Login() with OIDC
     * Authorization Code Flow.
     * Enforces strict CSRF and requires verified human ROLE_APPROVER authority.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http, GrantedAuthoritiesMapper authoritiesMapper)
            throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login/**", "/oauth2/**").permitAll()
                        .requestMatchers("/approvals/static/**", "/favicon.ico").permitAll()
                        .requestMatchers("/approvals", "/approvals/**", "/approvals.html").hasAuthority("ROLE_APPROVER")
                        .anyRequest().denyAll())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(authoritiesMapper))
                        .defaultSuccessUrl("/approvals", true))
                .logout(logout -> logout
                        .logoutUrl("/approvals/logout")
                        .logoutSuccessUrl("/approvals")
                        .deleteCookies("JSESSIONID")
                        .invalidateHttpSession(true));

        return http.build();
    }
}
