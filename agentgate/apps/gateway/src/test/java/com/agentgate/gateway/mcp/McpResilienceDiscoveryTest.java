package com.agentgate.gateway.mcp;

import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.resilience.circuitbreaker.CircuitBreakerState;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreaker;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
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
@DisplayName("MCP Resilience Discovery Filtering & Recovery Integration Tests (FR10)")
public class McpResilienceDiscoveryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private ToolCircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private McpToolRegistrationListener registrationListener;

    private static boolean fixturesInitialized = false;

    private final String AGENT_FINANCE = "agent-finance-bot";
    private final String AGENT_SUPPORT = "agent-support-bot";
    private final String TOOL_GET = "orders_get";
    private final String TOOL_REFUND = "orders_refund";

    @BeforeEach
    void setUp() {
        if (!fixturesInitialized) {
            RegisteredTool ordersGet = new RegisteredTool(
                    "tool-orders-get",
                    TOOL_GET,
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
                    TOOL_REFUND,
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

            toolRepository.addPermission(TOOL_GET, AGENT_SUPPORT);
            toolRepository.addPermission(TOOL_GET, AGENT_FINANCE);
            toolRepository.addPermission(TOOL_REFUND, AGENT_FINANCE);

            registrationListener.onApplicationReady();
            fixturesInitialized = true;
        }

        // Reset breakers and ensure tools enabled before each test
        circuitBreakerRegistry.resetAll();
        toolRepository.findByName(TOOL_GET).ifPresent(t -> toolRepository.save(new RegisteredTool(
                t.id(), t.name(), t.description(), t.inputSchema(), t.riskLevel(), t.owningTeam(),
                t.endpointUrl(), t.protocolType(), t.httpMethod(), true, t.createdAt(), Instant.now()
        )));
        toolRepository.findByName(TOOL_REFUND).ifPresent(t -> toolRepository.save(new RegisteredTool(
                t.id(), t.name(), t.description(), t.inputSchema(), t.riskLevel(), t.owningTeam(),
                t.endpointUrl(), t.protocolType(), t.httpMethod(), true, t.createdAt(), Instant.now()
        )));
    }

    @AfterEach
    void tearDown() {
        // Clean up any active emergency switches
        try {
            killSwitchService.deactivate(KillSwitchType.AGENT, AGENT_FINANCE, "admin");
        } catch (Exception ignored) {}
        try {
            killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_REFUND, "admin");
        } catch (Exception ignored) {}
        try {
            killSwitchService.deactivate(KillSwitchType.GLOBAL_WRITE, "*", "admin");
        } catch (Exception ignored) {}
        circuitBreakerRegistry.resetAll();
    }

    private AgentAuthenticationToken createAgentToken(String clientId, Set<String> scopes, Set<String> roles) {
        AgentIdentity identity = new AgentIdentity(clientId, scopes, roles);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
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
                        "clientInfo": {"name": "test-resilience-client", "version": "1.0.0"}
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

        return result.getResponse().getHeader("Mcp-Session-Id");
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
    @DisplayName("1. Healthy authorized tools appear in MCP discovery")
    void healthyAuthorizedTool_appearsInDiscovery() throws Exception {
        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""));
        assertTrue(toolsResponse.contains("\"name\":\"orders_refund\""));
        assertTrue(toolsResponse.contains("\"name\":\"m01_status\""));
        assertTrue(toolsResponse.contains("\"name\":\"get_approval_result\""));
    }

    @Test
    @DisplayName("2. Unauthorized tools remain hidden from unauthorized agents")
    void unauthorizedTool_remainsHidden() throws Exception {
        AgentAuthenticationToken supportAuth = createAgentToken(AGENT_SUPPORT, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(supportAuth);

        String toolsResponse = listTools(supportAuth, sessionId);

        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""));
        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "orders_refund must NOT leak to support agent");
    }

    @Test
    @DisplayName("3. Disabled tools remain hidden from MCP discovery")
    void disabledTool_remainsHidden() throws Exception {
        RegisteredTool refund = toolRepository.findByName(TOOL_REFUND).orElseThrow();
        toolRepository.save(new RegisteredTool(
                refund.id(), refund.name(), refund.description(), refund.inputSchema(), refund.riskLevel(),
                refund.owningTeam(), refund.endpointUrl(), refund.protocolType(), refund.httpMethod(),
                false, // disabled
                refund.createdAt(), Instant.now()
        ));

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "Disabled tool must NOT appear in tools/list");
        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""),
                "Enabled tool orders_get should remain visible");
    }

    @Test
    @DisplayName("4. Agent kill switch hides matching governed tools for targeted agent")
    void agentKillSwitch_hidesGovernedTools() throws Exception {
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_FINANCE, "Compromised bot", "admin");

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        assertFalse(toolsResponse.contains("\"name\":\"orders_get\""),
                "Governed tool orders_get must be hidden when agent is kill-switched");
        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "Governed tool orders_refund must be hidden when agent is kill-switched");

        // Built-in metadata tools remain discoverable
        assertTrue(toolsResponse.contains("\"name\":\"get_approval_result\""));
        assertTrue(toolsResponse.contains("\"name\":\"m01_status\""));
    }

    @Test
    @DisplayName("5. Tool kill switch hides the affected tool while leaving other tools visible")
    void toolKillSwitch_hidesAffectedToolOnly() throws Exception {
        killSwitchService.activate(KillSwitchType.TOOL, TOOL_REFUND, "Downstream payment incident", "admin");

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "Kill-switched tool orders_refund must be hidden");
        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""),
                "Healthy tool orders_get must remain discoverable");
    }

    @Test
    @DisplayName("6. Global-write switch hides mutating tools but preserves read-only tools")
    void globalWriteSwitch_hidesMutatingTools_preservesReads() throws Exception {
        killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, "*", "Global write freeze", "admin");

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        // orders_refund is a POST (write operation) -> hidden
        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "Mutating tool orders_refund must be hidden under global write freeze");

        // orders_get is a GET (read operation) -> preserved
        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""),
                "Read-only tool orders_get must remain discoverable under global write freeze");
    }

    @Test
    @DisplayName("7 & 8 & 9. Three downstream failures open circuit; tool disappears from tools/list while healthy tools remain")
    void openCircuit_disappearsFromToolsList() throws Exception {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        assertEquals(CircuitBreakerState.CLOSED, refundBreaker.getState());

        // Trip breaker with 3 failures
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, refundBreaker.getState());

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);

        // Tripped tool orders_refund must disappear from MCP discovery
        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "orders_refund must disappear from tools/list when its circuit is OPEN");

        // Other healthy tools remain discoverable
        assertTrue(toolsResponse.contains("\"name\":\"orders_get\""),
                "orders_get has CLOSED circuit and must remain discoverable");
        assertTrue(toolsResponse.contains("\"name\":\"m01_status\""));
        assertTrue(toolsResponse.contains("\"name\":\"get_approval_result\""));
    }

    @Test
    @DisplayName("10 & 11. Cooldown elapsed restores discovery; repeated tools/list calls never consume trial permit")
    void cooldownElapsed_restoresDiscovery_withoutConsumingTrialPermit() throws Exception {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, refundBreaker.getState());

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        // While OPEN within cooldown: hidden
        String responseBefore = listTools(financeAuth, sessionId);
        assertFalse(responseBefore.contains("\"name\":\"orders_refund\""));

        // Wait for cooldown (in test profile default-open-wait-duration is 1s)
        Thread.sleep(1100);

        // Verify read-only availability
        assertTrue(refundBreaker.isAvailableForDiscovery(),
                "Breaker should report available for discovery once cooldown elapses");

        // Repeated tools/list calls
        for (int i = 0; i < 5; i++) {
            String responseAfter = listTools(financeAuth, sessionId);
            assertTrue(responseAfter.contains("\"name\":\"orders_refund\""),
                    "orders_refund should reappear in tools/list after cooldown elapsed");
            // INVARIANT: tools/list MUST NOT consume the single HALF_OPEN trial permit
            assertFalse(refundBreaker.isHalfOpenTrialInFlight(),
                    "tools/list must never consume the in-flight trial permit!");
        }
    }

    @Test
    @DisplayName("12. Single concurrent trial execution in HALF_OPEN")
    void singleConcurrentTrial_inHalfOpen() throws Exception {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();

        // Wait for 1s test cooldown
        Thread.sleep(1100);

        // First execution acquires single trial permit
        assertTrue(refundBreaker.tryAcquirePermission(), "First caller should acquire the trial permit");
        assertTrue(refundBreaker.isHalfOpenTrialInFlight(), "Trial permit should be in-flight");

        // Second concurrent call is fast-rejected (anti-stampede)
        assertFalse(refundBreaker.tryAcquirePermission(), "Concurrent call must be rejected while trial is in-flight");
    }

    @Test
    @DisplayName("13. Successful trial restores CLOSED state and normal discovery")
    void successfulTrial_restoresClosedState() throws Exception {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();

        Thread.sleep(1100);
        assertTrue(refundBreaker.tryAcquirePermission());

        // Probe succeeds
        refundBreaker.onSuccess();
        assertEquals(CircuitBreakerState.CLOSED, refundBreaker.getState());
        assertEquals(0, refundBreaker.getConsecutiveFailures());

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);
        assertTrue(toolsResponse.contains("\"name\":\"orders_refund\""));
    }

    @Test
    @DisplayName("14. Failed trial reopens circuit and hides tool again")
    void failedTrial_reopensCircuitAndHidesTool() throws Exception {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();

        Thread.sleep(1100);
        assertTrue(refundBreaker.tryAcquirePermission());

        // Probe fails downstream
        refundBreaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, refundBreaker.getState());

        AgentAuthenticationToken financeAuth = createAgentToken(AGENT_FINANCE, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(financeAuth);

        String toolsResponse = listTools(financeAuth, sessionId);
        assertFalse(toolsResponse.contains("\"name\":\"orders_refund\""),
                "orders_refund must disappear again after failed trial probe");
    }

    @Test
    @DisplayName("15. Direct and approved execution pipelines share circuit state")
    void directAndApproved_shareCircuitState() {
        ToolCircuitBreaker refundBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        assertEquals(CircuitBreakerState.CLOSED, refundBreaker.getState());

        // Trip circuit
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        refundBreaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, refundBreaker.getState());

        // Breaker retrieved for approved execution sees exact same OPEN state
        ToolCircuitBreaker sharedBreaker = circuitBreakerRegistry.getBreaker(TOOL_REFUND);
        assertSame(refundBreaker, sharedBreaker);
        assertFalse(sharedBreaker.tryAcquirePermission());
    }
}
