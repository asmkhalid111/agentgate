package com.agentgate.demotools;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class DemoApiSecurity {
    @Bean
    SecurityFilterChain demoSecurity(HttpSecurity http, @Value("${agentgate.demo.token:}") String token)
            throws Exception {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .anyRequest().hasRole("DEMO_SERVICE"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        problem(response, 401, "Unauthorized"))
                        .accessDeniedHandler((request, response, exception) -> problem(response, 403, "Forbidden")))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        String supplied = request.getHeader("X-AgentGate-Demo-Token");
                        if (expected.length > 0 && supplied != null
                                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                    "agentgate-service", null, AuthorityUtils.createAuthorityList("ROLE_DEMO_SERVICE")));
                            SecurityContextHolder.setContext(context);
                        }
                        chain.doFilter(request, response);
                    }
                }, AuthorizationFilter.class)
                .build();
    }

    private static void problem(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d}
                """.formatted(title, status));
    }
}
