package com.agentgate.gateway.mcp;

import com.agentgate.gateway.GatewayApplication;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditVerificationResult;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.audit.service.AuditVerificationService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.pipeline.McpDownstreamToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.event.ToolRegisteredEvent;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.testsupport.DownstreamMcpTestServerApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for FR1 Real Downstream MCP Server Interoperability.
 * Spins up an actual embedded Streamable HTTP MCP server on an ephemeral port and
 * verifies discovery, authorized execution, unauthorized blocking, SSRF rejection,
 * and SHA-256 audit-chain integrity through the real AgentGate gateway.
 */
@SpringBootTest(classes = GatewayApplication.class, properties =
        "spring.datasource.url=jdbc:h2:mem:agentgate_downstream_mcp_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("FR1 Real Downstream MCP Integration Tests")
class McpDownstreamIntegrationTest {

    private static ConfigurableApplicationContext downstreamServerContext;
    private static int downstreamPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private StructuredPolicyEvaluator policyEvaluator;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditVerificationService auditVerificationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private McpDownstreamToolAdapter mcpDownstreamToolAdapter;

    @BeforeAll
    static void startRealDownstreamMcpServer() {
        SpringApplication app = new SpringApplication(DownstreamMcpTestServerApp.class);
        app.setAdditionalProfiles("test");
        downstreamServerContext = app.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.flyway.enabled=false",
                "--spring.ai.mcp.server.enabled=false",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration,org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
        );
        downstreamPort = Integer.parseInt(downstreamServerContext.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopDownstreamMcpServer() {
        if (downstreamServerContext != null) {
            downstreamServerContext.close();
        }
    }

    @BeforeEach
    void setUp() {
        auditRepository.resetToGenesis();

        RegisteredTool inventoryTool = new RegisteredTool(
                "tool-inventory-check",
                "inventory_check",
                "Check warehouse inventory availability for a product SKU",
                "{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}},\"required\":[\"sku\"]}",
                RiskLevel.LOW,
                "Inventory Team",
                "http://127.0.0.1:" + downstreamPort + "/mcp",
                ProtocolType.MCP,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(inventoryTool);
        toolRepository.addPermission("inventory_check", "agent-support-bot");
        eventPublisher.publishEvent(new ToolRegisteredEvent(inventoryTool));

        policyRepository.save(new PolicyRule(
                "pol-allow-inventory-check",
                "Support Bot Inventory Check",
                "agent-support-bot",
                "inventory_check",
                List.of(),
                PolicyAction.ALLOW,
                false,
                15,
                1,
                true
        ));
        policyEvaluator.reloadPolicies();
    }

    private AgentAuthenticationToken agentAuth(String clientId) {
        return new AgentAuthenticationToken(
                new AgentIdentity(clientId, Set.of("agent:invoke"), Set.of("ROLE_AGENT")),
                null,
                List.of(new SimpleGrantedAuthority("SCOPE_agent:invoke"), new SimpleGrantedAuthority("ROLE_AGENT"))
        );
    }

    private String initSession(AgentAuthenticationToken auth) throws Exception {
        String initReq = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "fr1-test-client", "version": "1.0.0"}
                  }
                }
                """;
        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initReq))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getHeader("Mcp-Session-Id");
    }

    @Test
    @DisplayName("FR1 E2E: Real downstream MCP server discovery, governed invocation, unauthorized block, and audit integrity")
    void realDownstreamMcp_discoveryInvocationAuthorizationAndAudit() throws Exception {
        AgentAuthenticationToken supportAuth = agentAuth("agent-support-bot");
        AgentAuthenticationToken financeAuth = agentAuth("agent-finance-bot");

        String supportSession = initSession(supportAuth);
        String financeSession = initSession(financeAuth);

        // 1. Permitted agent discovers inventory_check via tools/list
        String listReq = """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """;
        String supportListResp = mockMvc.perform(post("/mcp")
                        .with(authentication(supportAuth))
                        .header("Mcp-Session-Id", supportSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(listReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(supportListResp).contains("inventory_check");

        // 2. Unauthorized agent does NOT discover inventory_check
        String financeListResp = mockMvc.perform(post("/mcp")
                        .with(authentication(financeAuth))
                        .header("Mcp-Session-Id", financeSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(listReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(financeListResp).doesNotContain("inventory_check");

        // 3. Permitted agent invokes inventory_check -> real downstream MCP server executes and returns result
        String callReq = """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tools/call",
                  "params": {
                    "name": "inventory_check",
                    "arguments": {"sku": "SKU-DHAKA-777"}
                  }
                }
                """;
        String callResp = mockMvc.perform(post("/mcp")
                        .with(authentication(supportAuth))
                        .header("Mcp-Session-Id", supportSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(callResp)
                .contains("SKU-DHAKA-777")
                .contains("DHAKA-HUB-01")
                .contains("42")
                .doesNotContain("\"isError\":true");

        // 4. Unauthorized agent attempting direct tools/call is blocked
        String unauthCallResp = mockMvc.perform(post("/mcp")
                        .with(authentication(financeAuth))
                        .header("Mcp-Session-Id", financeSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unauthCallResp).contains("\"isError\":true");
        assertThat(unauthCallResp).contains("not authorized");

        // 5. Audit trail and SHA-256 chain integrity verification
        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).toolName()).isEqualTo("inventory_check");
        assertThat(events.get(0).agentId()).isEqualTo("agent-support-bot");
        assertThat(events.get(0).outcome()).isEqualTo("PENDING_DISPATCH");
        assertThat(events.get(1).toolName()).isEqualTo("inventory_check");
        assertThat(events.get(1).agentId()).isEqualTo("agent-support-bot");
        assertThat(events.get(1).outcome()).isEqualTo("SUCCESS");
        assertThat(events.get(2).agentId()).isEqualTo("agent-finance-bot");
        assertThat(events.get(2).outcome()).isEqualTo("BLOCKED");

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
        assertNull(verification.corruptedSequence());
        assertEquals(3L, verification.recordsChecked());
    }

    @Test
    @DisplayName("FR1 Security: McpDownstreamToolAdapter rejects disallowed SSRF destinations and credentials")
    void adapter_rejectsDisallowedEndpointHostAndCredentials() {
        RegisteredTool ssrfTool = new RegisteredTool(
                "tool-ssrf", "inventory_check", "SSRF test", "{}", RiskLevel.LOW, "Team",
                "http://169.254.169.254/latest/meta-data/mcp", ProtocolType.MCP, "POST", true, Instant.now(), Instant.now()
        );
        ToolExecutionResult ssrfResult = mcpDownstreamToolAdapter.execute(ssrfTool,
                new ToolExecutionContext("agent-support-bot", "inventory_check", Map.of("sku", "X"), null, RiskLevel.LOW));
        assertFalse(ssrfResult.success());
        assertThat(ssrfResult.error()).contains("Disallowed MCP endpoint host");

        RegisteredTool userInfoTool = new RegisteredTool(
                "tool-cred", "inventory_check", "Cred test", "{}", RiskLevel.LOW, "Team",
                "http://user:pass@127.0.0.1:8082/mcp", ProtocolType.MCP, "POST", true, Instant.now(), Instant.now()
        );
        ToolExecutionResult credResult = mcpDownstreamToolAdapter.execute(userInfoTool,
                new ToolExecutionContext("agent-support-bot", "inventory_check", Map.of("sku", "X"), null, RiskLevel.LOW));
        assertFalse(credResult.success());
        assertThat(credResult.error()).contains("userinfo credentials");
    }
}
