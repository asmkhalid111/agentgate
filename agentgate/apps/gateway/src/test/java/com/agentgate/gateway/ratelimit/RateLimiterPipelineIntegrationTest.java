package com.agentgate.gateway.ratelimit;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.mcp.McpToolRegistrationListener;
import com.agentgate.gateway.pipeline.ApprovedToolExecutionService;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.ConditionOperator;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.domain.StructuredCondition;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Rate Limiter Pipeline & MCP Integration Tests (FR6)")
class RateLimiterPipelineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private ApprovedToolExecutionService approvedExecutionService;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private StructuredPolicyEvaluator policyEvaluator;

    @Autowired
    private McpToolRegistrationListener registrationListener;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    private static final String FINANCE_AGENT = "agent-finance-bot";
    private static final String SUPPORT_AGENT = "agent-support-bot";
    private static final String RL_TOOL = "rl_governed_tool";
    private static final String HIGH_RISK_TOOL = "rl_high_risk_tool";

    @BeforeEach
    void setUp() {
        cleanupRedis();

        rateLimitProperties.setEnabled(true);
        rateLimitProperties.setDefaultAgentRpm(60);
        rateLimitProperties.setDefaultToolRpm(120);
        rateLimitProperties.getAgentLimits().clear();
        rateLimitProperties.getToolLimits().clear();

        // Register low-risk test tool
        RegisteredTool tool = new RegisteredTool(
                "tool-rl-" + System.nanoTime(),
                RL_TOOL,
                "Rate limit test tool",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}",
                RiskLevel.LOW,
                "Core Engineering",
                "http://localhost:8082/api/test/rl",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(tool);
        toolRepository.addPermission(RL_TOOL, FINANCE_AGENT);
        toolRepository.addPermission(RL_TOOL, SUPPORT_AGENT);

        // Register high-risk test tool
        RegisteredTool highRiskTool = new RegisteredTool(
                "tool-rl-hr-" + System.nanoTime(),
                HIGH_RISK_TOOL,
                "High risk rate limit test tool",
                "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}},\"required\":[\"amount\"]}",
                RiskLevel.HIGH,
                "Core Engineering",
                "http://localhost:8082/api/test/rl-hr",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(highRiskTool);
        toolRepository.addPermission(HIGH_RISK_TOOL, FINANCE_AGENT);

        // Add require approval policy for high risk tool
        PolicyRule approvalRule = new PolicyRule(
                "pol-rl-approval-1",
                "High Risk Approval Rule",
                "*",
                HIGH_RISK_TOOL,
                List.of(new StructuredCondition("amount", ConditionOperator.GT, 100)),
                PolicyAction.REQUIRE_APPROVAL,
                false,
                10,
                1,
                true
        );
        policyRepository.save(approvalRule);

        // Add allow policy for low risk tool
        PolicyRule allowRule = new PolicyRule(
                "pol-rl-allow-1",
                "Allow RL Tool",
                "*",
                RL_TOOL,
                List.of(),
                PolicyAction.ALLOW,
                false,
                100,
                1,
                true
        );
        policyRepository.save(allowRule);
        policyEvaluator.reloadPolicies();
        registrationListener.onApplicationReady();

        doReturn(ToolExecutionResult.success(Map.of("status", "SUCCESS"), 10L))
                .when(restToolAdapter).execute(any(), any());
    }

    @AfterEach
    void tearDown() {
        cleanupRedis();
    }

    private void cleanupRedis() {
        try {
            Set<String> keys = redisTemplate.keys("agentgate:ratelimit:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

    private AgentAuthenticationToken createAgentToken(String clientId) {
        AgentIdentity identity = new AgentIdentity(clientId, Set.of("agent:invoke"), Set.of("USER"));
        return new AgentAuthenticationToken(identity, "mock-credentials",
                List.of(new SimpleGrantedAuthority("SCOPE_agent:invoke"), new SimpleGrantedAuthority("ROLE_USER")));
    }

    private String initMcpSession(AgentAuthenticationToken auth) throws Exception {
        String initPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "clientInfo": {"name": "test-rl-client", "version": "1.0.0"}
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

    @Test
    @DisplayName("9. Policy DENY consumes the ingress allocation")
    void policyDeny_consumesIngressAllocation() {
        // Configure policy that DENIES input == 'blocked'
        PolicyRule denyRule = new PolicyRule(
                "pol-rl-deny-1",
                "Deny Specific Input",
                "*",
                RL_TOOL,
                List.of(new StructuredCondition("input", ConditionOperator.EQ, "blocked")),
                PolicyAction.DENY,
                false,
                5,
                1,
                true
        );
        policyRepository.save(denyRule);
        policyEvaluator.reloadPolicies();

        // Limit agent to 2 RPM
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 2);

        ToolExecutionContext ctxBlocked = new ToolExecutionContext(
                FINANCE_AGENT, RL_TOOL, Map.of("input", "blocked"), null, RiskLevel.LOW, Collections.emptySet()
        );

        // Call 1: Blocked by policy DENY, but consumes 1 allocation
        ToolExecutionResult res1 = pipeline.execute(ctxBlocked);
        assertFalse(res1.success());
        assertTrue(res1.error().contains("POLICY_DENIED"));

        // Call 2: Blocked by policy DENY, consumes 2nd allocation
        ToolExecutionResult res2 = pipeline.execute(ctxBlocked);
        assertFalse(res2.success());
        assertTrue(res2.error().contains("POLICY_DENIED"));

        // Call 3: Must be blocked by RATE_LIMIT_EXCEEDED before policy evaluation
        ToolExecutionContext ctxValid = new ToolExecutionContext(
                FINANCE_AGENT, RL_TOOL, Map.of("input", "valid"), null, RiskLevel.LOW, Collections.emptySet()
        );
        ToolExecutionResult res3 = pipeline.execute(ctxValid);
        assertFalse(res3.success());
        assertTrue(res3.error().contains("RATE_LIMIT_EXCEEDED"),
                "Expected RATE_LIMIT_EXCEEDED on 3rd call, got: " + res3.error());
    }

    @Test
    @DisplayName("10. REQUIRE_APPROVAL consumes one allocation at ingress")
    void requireApproval_consumesOneAllocationAtIngress() {
        // Limit agent to 1 RPM
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 1);

        ToolExecutionContext ctx = new ToolExecutionContext(
                FINANCE_AGENT, HIGH_RISK_TOOL, Map.of("amount", 200), null, RiskLevel.HIGH, Collections.emptySet()
        );

        // First call: held for approval, consumes 1 allocation
        ToolExecutionResult res1 = pipeline.execute(ctx);
        assertFalse(res1.success());
        assertTrue(res1.error().contains("REQUIRE_APPROVAL"));
        assertTrue(res1.error().contains("ApprovalId: appr-"));

        // Second call in same window: blocked by RATE_LIMIT_EXCEEDED
        ToolExecutionResult res2 = pipeline.execute(ctx);
        assertFalse(res2.success());
        assertTrue(res2.error().contains("RATE_LIMIT_EXCEEDED"),
                "Expected RATE_LIMIT_EXCEEDED on 2nd call, got: " + res2.error());
    }

    @Test
    @DisplayName("11. Approved execution is not counted a second time")
    void approvedExecution_isNotCountedSecondTime() throws Exception {
        // Limit agent to 1 RPM
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 1);

        ToolExecutionContext ctx = new ToolExecutionContext(
                FINANCE_AGENT, HIGH_RISK_TOOL, Map.of("amount", 200), null, RiskLevel.HIGH, Collections.emptySet()
        );

        // Call 1 consumes the 1 allowed token and creates approval request
        ToolExecutionResult res1 = pipeline.execute(ctx);
        assertTrue(res1.error().contains("ApprovalId: appr-"));
        String approvalId = res1.error().substring(res1.error().indexOf("appr-"), res1.error().indexOf(","));

        // Verify quota is currently exhausted for new ingress calls
        ToolExecutionResult resBlocked = pipeline.execute(ctx);
        assertTrue(resBlocked.error().contains("RATE_LIMIT_EXCEEDED"));

        // Human approves the request
        ApprovalRequest approved = approvalService.approve(approvalId, "human-approver", null);
        assertEquals(ApprovalStatus.APPROVED, approved.status());

        // Approved tool execution executes via event/listener without failing on rate limit!
        ApprovalRequest completed = null;
        for (int i = 0; i < 30; i++) {
            completed = approvalRepository.findById(approvalId).orElseThrow();
            if (completed.executionState() == ExecutionState.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(completed);
        assertEquals(ExecutionState.COMPLETED, completed.executionState(),
                "Approved execution should succeed without being double-counted against rate limit");
        verify(restToolAdapter, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("12. Rate-limit denial prevents approval persistence and downstream dispatch")
    void rateLimitDenial_preventsApprovalPersistenceAndDispatch() {
        // Limit agent to 1 RPM
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 1);

        ToolExecutionContext ctx = new ToolExecutionContext(
                FINANCE_AGENT, HIGH_RISK_TOOL, Map.of("amount", 200), null, RiskLevel.HIGH, Collections.emptySet()
        );

        // 1st call creates 1 approval
        pipeline.execute(ctx);
        int initialApprovalCount = approvalRepository.findAll().size();

        // 2nd call hits rate limit
        ToolExecutionResult res2 = pipeline.execute(ctx);
        assertTrue(res2.error().contains("RATE_LIMIT_EXCEEDED"));

        // Approval repository count must NOT increase
        assertEquals(initialApprovalCount, approvalRepository.findAll().size(),
                "Rate-limited request must NOT persist an approval request");
        verify(restToolAdapter, never()).execute(any(), any());
    }

    @Test
    @DisplayName("13. MCP returns expected safe error when rate limit is exceeded")
    void mcp_returnsExpectedSafeError() throws Exception {
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 1);

        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT);
        String sessionId = initMcpSession(auth);

        String callPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 101,
                    "method": "tools/call",
                    "params": {
                        "name": "%s",
                        "arguments": {"input": "hello"}
                    }
                }
                """.formatted(RL_TOOL);

        // Call 1: Success
        mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callPayload))
                .andExpect(status().isOk());

        // Call 2: Rate limit exceeded
        MvcResult result2 = mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callPayload))
                .andExpect(status().isOk())
                .andReturn();

        String responseContent = result2.getResponse().getContentAsString();
        assertTrue(responseContent.contains("RATE_LIMIT_EXCEEDED"),
                "MCP response must contain RATE_LIMIT_EXCEEDED: " + responseContent);
        assertTrue(responseContent.contains("Agent or tool request limit exceeded. Retry after the current window resets."),
                "MCP response must contain safe retry guidance");
        assertTrue(responseContent.contains("\"isError\":true") || responseContent.contains("\"isError\": true"),
                "MCP response must set isError: true");

        // Ensure no internal Redis keys or credentials leaked
        assertFalse(responseContent.contains("agentgate:ratelimit:"));
        assertFalse(responseContent.contains("localhost"));
        assertFalse(responseContent.contains("6379"));
    }

    @Test
    @DisplayName("14. Built-in metadata tools (get_approval_result) are not affected by governed rate limiter")
    void builtInMetadataTools_notGovernedByRateLimiter() throws Exception {
        // Set agent limit to 1 RPM
        rateLimitProperties.getAgentLimits().put(FINANCE_AGENT, 1);

        // Consume the 1 allowed token for RL_TOOL
        ToolExecutionContext ctx = new ToolExecutionContext(
                FINANCE_AGENT, RL_TOOL, Map.of("input", "test"), null, RiskLevel.LOW, Collections.emptySet()
        );
        assertTrue(pipeline.execute(ctx).success());
        assertTrue(pipeline.execute(ctx).error().contains("RATE_LIMIT_EXCEEDED"));

        // Now call get_approval_result via MCP as FINANCE_AGENT
        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT);
        String sessionId = initMcpSession(auth);

        String callPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 102,
                    "method": "tools/call",
                    "params": {
                        "name": "get_approval_result",
                        "arguments": {"approvalId": "appr-non-existent"}
                    }
                }
                """;

        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(auth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(callPayload))
                .andExpect(status().isOk())
                .andReturn();

        String sseOutput = result.getResponse().getContentAsString();
        // Should return NOT_FOUND (normal response), NOT RATE_LIMIT_EXCEEDED
        assertTrue(sseOutput.contains("NOT_FOUND"), "Built-in metadata tool should not be blocked by rate limit: " + sseOutput);
        assertFalse(sseOutput.contains("RATE_LIMIT_EXCEEDED"), "Built-in metadata tool must not be rate limited");
    }
}
