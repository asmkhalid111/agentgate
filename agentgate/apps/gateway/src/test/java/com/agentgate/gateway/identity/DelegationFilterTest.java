package com.agentgate.gateway.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelegationFilterTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnsignedUserId() throws Exception {
        assertEquals(401, check("X-User-Id", "customer"));
    }

    @Test
    void rejectsExpiredAndWrongClientAssertions() throws Exception {
        when(decoder.decode("expired")).thenReturn(assertion("finance", Instant.now().minusSeconds(1)));
        when(decoder.decode("wrong-client")).thenReturn(assertion("other", Instant.now().plusSeconds(60)));
        assertEquals(401, check("X-User-Assertion", "expired"));
        assertEquals(401, check("X-User-Assertion", "wrong-client"));
    }

    private Jwt assertion(String client, Instant expiry) {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").subject("user")
                .audience(List.of("agentgate-gateway")).claim("scope", "tool:delegate")
                .claim("azp", client).expiresAt(expiry).build();
    }

    private int check(String name, String value) throws Exception {
        var agent = new AgentIdentity("finance", Set.of("delegation:act_as_user"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new AgentAuthenticationToken(agent, null, List.of()));
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(name, value);
        var response = new MockHttpServletResponse();
        new DelegationFilter(decoder).doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
