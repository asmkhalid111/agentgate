package com.agentgate.gateway.mcp;

import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public class McpDiscoveryFilteringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private ObjectProvider<McpSyncServer> mcpSyncServerProvider;

    @Autowired
    private McpToolRegistrationListener registrationListener;

    private static boolean initialized = false;

    @BeforeEach
    void setupFixtures() {
        if (!initialized) {
            // Seed two dynamic tools for testing discovery filtering
            RegisteredTool ordersGet = new RegisteredTool(
                    "tool-orders-get",
                    "orders_get",
                    "Retrieve order details by ID",
                    "{\"type\":\"object\"}",
                    RiskLevel.LOW,
                    "Order Management",
                    "http://localhost:8082/api/orders/get",
                    ProtocolType.REST,
                    "GET",
                    true,
                    Instant.now(),
                    Instant.now()
            );
            RegisteredTool ordersRefund = new RegisteredTool(
                    "tool-orders-refund",
                    "orders_refund",
                    "Process customer refund",
                    "{\"type\":\"object\"}",
                    RiskLevel.HIGH,
                    "Finance Operations",
                    "http://localhost:8082/api/orders/refund",
                    ProtocolType.REST,
                    "POST",
                    true,
                    Instant.now(),
                    Instant.now()
            );

            toolRepository.save(ordersGet);
            toolRepository.save(ordersRefund);

            // Configure permissions:
            // orders_get is permitted for both agent-support-bot and agent-finance-bot
            toolRepository.addPermission("orders_get", "agent-support-bot");
            toolRepository.addPermission("orders_get", "agent-finance-bot");

            // orders_refund is permitted ONLY for agent-finance-bot
            toolRepository.addPermission("orders_refund", "agent-finance-bot");

            // Ensure McpSyncServer has all tools loaded
            registrationListener.onApplicationReady();
            initialized = true;
        }
    }

    private AgentAuthenticationToken createAgentToken(String clientId, Set<String> scopes, Set<String> roles) {
        AgentIdentity identity = new AgentIdentity(clientId, scopes, roles);
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new AgentAuthenticationToken(identity, "mock-credentials", authorities);
    }

    private String initializeSession(AgentAuthenticationToken auth) throws Exception {
        String initPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "clientInfo": {"name": "test-discovery-client", "version": "1.0.0"}
                    }
                }
                """;

        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initPayload))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId = result.getResponse().getHeader("Mcp-Session-Id");
        assertNotNull(sessionId, "Mcp-Session-Id header must be returned on initialize");
        return sessionId;
    }

    private String listTools(AgentAuthenticationToken auth, String sessionId) throws Exception {
        String listPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list",
                    "params": {}
                }
                """;

        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(listPayload))
                .andExpect(status().isOk())
                .andReturn();

        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("Support agent sees only permitted tools in MCP tools/list")
    void supportAgent_seesOnlyPermittedTools() throws Exception {
        AgentAuthenticationToken supportAuth = createAgentToken(
                "agent-support-bot",
                Set.of("agent:invoke"),
                Set.of("USER")
        );

        String sessionId = initializeSession(supportAuth);
        String sseResponse = listTools(supportAuth, sessionId);

        assertNotNull(sseResponse);
        // Permitted baseline tool
        assertTrue(sseResponse.contains("\"name\":\"m01_status\""), "m01_status should be visible to support bot");
        // Permitted dynamic tool
        assertTrue(sseResponse.contains("\"name\":\"orders_get\""), "orders_get should be visible to support bot");

        // FORBIDDEN tools: must NOT be exposed to support agent
        assertFalse(sseResponse.contains("\"name\":\"orders_refund\""),
                "orders_refund must NOT be visible to support bot in tools/list");
        assertFalse(sseResponse.contains("\"name\":\"m01_restricted_admin_tool\""),
                "m01_restricted_admin_tool must NOT be visible to support bot in tools/list");
    }

    @Test
    @DisplayName("Finance agent sees all its permitted tools including orders_refund and admin fixtures")
    void financeAgent_seesPermittedTools() throws Exception {
        AgentAuthenticationToken financeAuth = createAgentToken(
                "agent-finance-bot",
                Set.of("agent:invoke", "tool:admin"),
                Set.of("ADMIN", "USER")
        );

        String sessionId = initializeSession(financeAuth);
        String sseResponse = listTools(financeAuth, sessionId);

        assertNotNull(sseResponse);
        assertTrue(sseResponse.contains("\"name\":\"m01_status\""), "m01_status should be visible to finance bot");
        assertTrue(sseResponse.contains("\"name\":\"orders_get\""), "orders_get should be visible to finance bot");
        assertTrue(sseResponse.contains("\"name\":\"orders_refund\""), "orders_refund should be visible to finance bot");
        assertTrue(sseResponse.contains("\"name\":\"m01_restricted_admin_tool\""),
                "m01_restricted_admin_tool should be visible to admin finance bot");
    }

    @Test
    @DisplayName("Unauthorized agent cannot list tools (blocked with 403 Forbidden)")
    void unauthorizedAgent_cannotListTools() throws Exception {
        AgentAuthenticationToken unauth = createAgentToken(
                "agent-unauthorized-bot",
                Set.of(),
                Set.of()
        );

        String initPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "clientInfo": {"name": "test-discovery-client", "version": "1.0.0"}
                    }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .with(authentication(unauth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initPayload))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Support agent cannot invoke orders_refund directly even by knowing its name")
    void supportAgent_cannotInvokeOrdersRefundDirectly() throws Exception {
        AgentAuthenticationToken supportAuth = createAgentToken(
                "agent-support-bot",
                Set.of("agent:invoke"),
                Set.of("USER")
        );

        String sessionId = initializeSession(supportAuth);

        String callPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 105,
                    "method": "tools/call",
                    "params": {
                        "name": "orders_refund",
                        "arguments": {
                            "orderId": "ORD-999",
                            "amount": 500
                        }
                    }
                }
                """;

        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(supportAuth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callPayload))
                .andExpect(status().isOk())
                .andReturn();

        String sseContent = result.getResponse().getContentAsString();
        // The MCP call response must indicate failure/error (isError: true or error message)
        assertTrue(sseContent.contains("isError") || sseContent.contains("error") || sseContent.contains("not authorized"),
                "Direct invocation of unauthorized orders_refund must fail for support agent");
        assertFalse(sseContent.contains("Refund processed successfully"),
                "Unauthorized orders_refund must NOT execute successfully");
    }

    @Test
    @DisplayName("Separate authenticated requests do not leak one agent's tool visibility into another's")
    void separateRequests_doNotLeakVisibilityAcrossAgents() throws Exception {
        AgentAuthenticationToken financeAuth = createAgentToken(
                "agent-finance-bot",
                Set.of("agent:invoke", "tool:admin"),
                Set.of("ADMIN", "USER")
        );
        AgentAuthenticationToken supportAuth = createAgentToken(
                "agent-support-bot",
                Set.of("agent:invoke"),
                Set.of("USER")
        );

        // Session 1: Finance agent
        String financeSession = initializeSession(financeAuth);
        String financeResp1 = listTools(financeAuth, financeSession);
        assertTrue(financeResp1.contains("\"name\":\"orders_refund\""), "Finance bot must see orders_refund in call 1");

        // Session 2: Support agent immediately after
        String supportSession = initializeSession(supportAuth);
        String supportResp = listTools(supportAuth, supportSession);
        assertFalse(supportResp.contains("\"name\":\"orders_refund\""), "Support bot must NOT see orders_refund");

        // Session 3: Finance agent again
        String financeResp2 = listTools(financeAuth, financeSession);
        assertTrue(financeResp2.contains("\"name\":\"orders_refund\""), "Finance bot must see orders_refund in call 3");
    }
}
