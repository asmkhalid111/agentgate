package com.agentgate.gateway.config;

import com.agentgate.gateway.identity.JwtAgentAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAgentAuthenticationConverter jwtAgentAuthenticationConverter() {
        return new JwtAgentAuthenticationConverter();
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
            throw new IllegalStateException("FATAL: 'spring.security.oauth2.resourceserver.jwt.jwk-set-uri' must not be blank in production/dev. Security requires a live Keycloak IdP.");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        if (issuerUri != null && !issuerUri.isBlank()) {
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
            decoder.setJwtValidator(withIssuer);
        }
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAgentAuthenticationConverter converter) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/mcp", "/mcp/**", "/actuator/**", "/api/v1/**")
            )
            .authorizeHttpRequests(auth -> auth
                // Public observability and documentation endpoints
                .requestMatchers("/actuator/health", "/actuator/info", "/error", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Protected MCP protocol endpoint (strictly requires agent:invoke scope)
                .requestMatchers("/mcp", "/mcp/**").hasAuthority("SCOPE_agent:invoke")
                // Protected Human Approval REST API (strictly requires ROLE_APPROVER)
                .requestMatchers("/api/v1/approvals", "/api/v1/approvals/**").hasAuthority("ROLE_APPROVER")
                // Policy playground endpoint (accessible to ADMIN and AGENT)
                .requestMatchers("/api/v1/policies/playground").hasAnyAuthority("ROLE_ADMIN", "ROLE_AGENT", "SCOPE_tool:admin", "SCOPE_agent:invoke")
                // Protected administrative management endpoints (FR1 tool registry, FR3 policies)
                .requestMatchers("/api/v1/registry/**", "/api/v1/policies/**", "/admin/**").hasAnyAuthority("ROLE_ADMIN", "SCOPE_tool:admin")
                // Deny all other unmapped routes (default deny)
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            );

        return http.build();
    }
}
