package com.agentgate.gateway.mcp;

import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class McpConnectivityTest {

    @Autowired
    private DemoToolsMcpProvider demoToolsMcpProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAgent(String clientId, Set<String> scopes, Set<String> roles) {
        AgentIdentity identity = new AgentIdentity(clientId, scopes, roles);
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        AgentAuthenticationToken token = new AgentAuthenticationToken(identity, "mock-credentials", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    @DisplayName("Permitted tool m01_status should succeed when called by authenticated agent")
    void m01_status_shouldSucceedForAuthenticatedAgent() {
        authenticateAgent("agent-support-bot", Set.of("agent:invoke"), Set.of("USER"));

        Map<String, Object> result = demoToolsMcpProvider.m01_status("ping");

        assertNotNull(result);
        assertEquals("HEALTHY", result.get("status"));
        assertEquals("agent-support-bot", result.get("callingAgent"));
        assertEquals("ping", result.get("echo"));
    }

    @Test
    @DisplayName("Restricted tool m01_restricted_admin_tool should succeed when called by authorized admin agent")
    void m01_restrictedAdminTool_shouldSucceedForAdminAgent() {
        authenticateAgent("agent-finance-bot", Set.of("agent:invoke", "tool:admin"), Set.of("ADMIN"));

        Map<String, Object> result = demoToolsMcpProvider.m01_restricted_admin_tool("database_backup");

        assertNotNull(result);
        assertEquals("EXECUTED", result.get("status"));
        assertEquals("agent-finance-bot", result.get("authorizedAgent"));
        assertEquals("database_backup", result.get("action"));
    }

    @Test
    @DisplayName("Restricted tool m01_restricted_admin_tool should throw AccessDeniedException when called by unauthorized agent")
    void m01_restrictedAdminTool_shouldFailForUnauthorizedAgent() {
        authenticateAgent("agent-support-bot", Set.of("agent:invoke"), Set.of("USER"));

        AccessDeniedException exception = assertThrows(AccessDeniedException.class, () -> {
            demoToolsMcpProvider.m01_restricted_admin_tool("unauthorized_action");
        });

        assertTrue(exception.getMessage().contains("Access Denied"));
        assertTrue(exception.getMessage().contains("agent-support-bot"));
    }
}

