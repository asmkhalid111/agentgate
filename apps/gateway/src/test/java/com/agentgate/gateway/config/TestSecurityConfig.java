package com.agentgate.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

/**
 * Isolated test-only JWT decoder configuration.
 * Strictly resides in src/test/java so it is NEVER packaged into production artifacts.
 * Automatically active whenever the 'test' profile is used during testing.
 */
@Configuration
@Profile("test")
public class TestSecurityConfig {

    @Bean
    public JwtDecoder testJwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "mock-agent")
                .claim("azp", "agent-finance-bot")
                .claim("scope", "agent:invoke")
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("AGENT", "USER")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
