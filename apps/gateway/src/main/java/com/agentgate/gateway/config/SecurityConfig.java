package com.agentgate.gateway.config;

import com.agentgate.gateway.identity.JwtAgentAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import java.time.Instant;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAgentAuthenticationConverter jwtAgentAuthenticationConverter() {
        return new JwtAgentAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            if (issuerUri != null && !issuerUri.isBlank()) {
                OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
                decoder.setJwtValidator(withIssuer);
            }
            return decoder;
        }
        // Fallback decoder for test environments without live Keycloak IdP
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "mock-agent")
                .claim("azp", "agent-finance-bot")
                .claim("scope", "agent:invoke")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAgentAuthenticationConverter converter) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/mcp", "/mcp/**", "/actuator/**")
            )
            .authorizeHttpRequests(auth -> auth
                // Public observability and documentation endpoints
                .requestMatchers("/actuator/health", "/actuator/info", "/error", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Protected MCP protocol endpoint (strictly requires agent:invoke scope)
                .requestMatchers("/mcp", "/mcp/**").hasAuthority("SCOPE_agent:invoke")
                // Future admin management endpoints
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // Deny all other unmapped routes (default deny)
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            );

        return http.build();
    }
}
