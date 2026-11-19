package com.agentgate.gateway.mcp;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import com.agentgate.gateway.approval.dto.ApprovalResultResponse;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentContextHolder;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.registry.controller.ToolRegistryController;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.registry.service.OpenApiImporterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
@DisplayName("Secure MCP Approval Result Retrieval Tests (M03 Task 10B / FR4)")
class ApprovalResultMcpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private ApprovalResultMcpProvider approvalResultMcpProvider;

    @Autowired
    private ToolRegistryController toolRegistryController;

    @Autowired
    private OpenApiImporterService openApiImporterService;

    @Autowired
    private McpToolRegistrationListener registrationListener;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    private static final String FINANCE_AGENT = "agent-finance-bot";
    private static final String SUPPORT_AGENT = "agent-support-bot";
    private static final String TOOL_NAME = "mcp_retrieval_test_tool";
    private static final String RULE_ID = "pol-retrieval-1";

    @BeforeEach
    void setUp() {
        RegisteredTool tool = new RegisteredTool(
                "tool-retrieval-" + System.nanoTime(),
                TOOL_NAME,
                "Test tool for MCP retrieval",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Core Engineering",
                "http://localhost:8082/api/test/retrieval",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(tool);
        toolRepository.addPermission(TOOL_NAME, FINANCE_AGENT);
        registrationListener.onApplicationReady();
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
                        "clientInfo": {"name": "test-retrieval-client", "version": "1.0.0"}
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

    private String callGetApprovalResultOverMcp(AgentAuthenticationToken auth, String sessionId, String approvalId) throws Exception {
        String callPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 100,
                    "method": "tools/call",
                    "params": {
                        "name": "get_approval_result",
                        "arguments": {
                            "approvalId": "%s"
                        }
                    }
                }
                """.formatted(approvalId);

        var reqBuilder = post("/mcp")
                .header("Mcp-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(callPayload);

        if (auth != null) {
            reqBuilder.with(authentication(auth));
        }

        MvcResult result = mockMvc.perform(reqBuilder).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("1. Authenticated finance and support agents discover get_approval_result in tools/list")
    void authenticatedAgents_discoverGetApprovalResult() throws Exception {
        AgentAuthenticationToken financeAuth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        AgentAuthenticationToken supportAuth = createAgentToken(SUPPORT_AGENT, Set.of("agent:invoke"), Set.of("USER"));

        String finSession = initializeSession(financeAuth);
        String finTools = listTools(financeAuth, finSession);
        assertTrue(finTools.contains("\"name\":\"get_approval_result\""), "Finance agent must discover get_approval_result");

        String supSession = initializeSession(supportAuth);
        String supTools = listTools(supportAuth, supSession);
        assertTrue(supTools.contains("\"name\":\"get_approval_result\""), "Support agent must discover get_approval_result");
    }

    @Test
    @DisplayName("2. Anonymous/invalid-token requests cannot invoke get_approval_result (fails closed)")
    void anonymousRequests_cannotInvokeTool() {
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () ->
                approvalResultMcpProvider.getApprovalResult("appr-some-id")
        );
    }

    @Test
    @DisplayName("3. Authenticated agent retrieves its own PENDING approval")
    void authenticatedAgent_retrievesPendingApproval() throws Exception {
        ApprovalRequest req = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of("amount", 2000.0), Duration.ofMinutes(15)
        );

        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        Map<String, Object> response = approvalResultMcpProvider.getApprovalResult(req.id());
        assertEquals(req.id(), response.get("approvalId"));
        assertEquals("PENDING", response.get("status"));
        assertEquals("NOT_DISPATCHED", response.get("executionState"));
        assertEquals("Awaiting human approval decision", response.get("message"));
        assertNull(response.get("result"));
        assertNull(response.get("error"));

        // Over MCP Streamable HTTP
        String sessionId = initializeSession(auth);
        String sseOutput = callGetApprovalResultOverMcp(auth, sessionId, req.id());
        assertTrue(sseOutput.contains("PENDING"), "MCP output must contain PENDING status");
    }

    @Test
    @DisplayName("4. Authenticated agent retrieves its own COMPLETED sanitized result")
    void authenticatedAgent_retrievesCompletedSanitizedResult() throws Exception {
        ApprovalRequest req = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of("amount", 2000.0), Duration.ofMinutes(15)
        );

        // Manually simulate approved + completed execution outcome in DB
        approvalRepository.transitionToApproved(req.id(), null, "manager@agentgate.com", Instant.now(), Instant.now());
        approvalRepository.claimExecution(req.id(), Instant.now());
        approvalRepository.recordExecutionSuccess(req.id(), "{\"orderId\":\"ORD-777\",\"status\":\"REFUNDED\"}", Instant.now());

        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        Map<String, Object> response = approvalResultMcpProvider.getApprovalResult(req.id());
        assertEquals("APPROVED", response.get("status"));
        assertEquals("COMPLETED", response.get("executionState"));
        assertNotNull(response.get("result"));
        assertTrue(response.get("result") instanceof Map);
        Map<?, ?> resultMap = (Map<?, ?>) response.get("result");
        assertEquals("REFUNDED", resultMap.get("status"));
        assertNull(response.get("error"));

        // Over MCP transport
        String sessionId = initializeSession(auth);
        String sseOutput = callGetApprovalResultOverMcp(auth, sessionId, req.id());
        assertTrue(sseOutput.contains("COMPLETED"));
        assertTrue(sseOutput.contains("REFUNDED"));
    }

    @Test
    @DisplayName("5. FAILED and INDETERMINATE states produce clear messages")
    void failedAndIndeterminateStates_produceClearMessages() {
        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 5a. FAILED
        ApprovalRequest reqFailed = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of(), Duration.ofMinutes(15)
        );
        approvalRepository.transitionToApproved(reqFailed.id(), null, "manager@agentgate.com", Instant.now(), Instant.now());
        approvalRepository.claimExecution(reqFailed.id(), Instant.now());
        approvalRepository.recordExecutionFailure(reqFailed.id(), "Downstream service 500 Internal Error", Instant.now());

        Map<String, Object> respFailed = approvalResultMcpProvider.getApprovalResult(reqFailed.id());
        assertEquals("APPROVED", respFailed.get("status"));
        assertEquals("FAILED", respFailed.get("executionState"));
        assertEquals("Downstream service 500 Internal Error", respFailed.get("error"));
        assertEquals("Execution failed", respFailed.get("message"));

        // 5b. INDETERMINATE
        ApprovalRequest reqIndet = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of(), Duration.ofMinutes(15)
        );
        approvalRepository.transitionToApproved(reqIndet.id(), null, "manager@agentgate.com", Instant.now(), Instant.now());
        approvalRepository.claimExecution(reqIndet.id(), Instant.now());
        approvalRepository.recordExecutionIndeterminate(reqIndet.id(), "Read timed out after 5000ms", Instant.now());

        Map<String, Object> respIndet = approvalResultMcpProvider.getApprovalResult(reqIndet.id());
        assertEquals("APPROVED", respIndet.get("status"));
        assertEquals("INDETERMINATE", respIndet.get("executionState"));
        assertEquals("Read timed out after 5000ms", respIndet.get("error"));
        assertTrue(String.valueOf(respIndet.get("message")).contains("Do not retry automatically"));
    }

    @Test
    @DisplayName("6. REJECTED, EXPIRED and CANCELLED states are correctly represented with generic safe messages")
    void nonApprovedDecisions_representedCorrectlyWithSafeMessages() {
        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 6a. REJECTED
        ApprovalRequest reqRej = approvalService.createRequest(FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of(), Duration.ofMinutes(15));
        approvalService.reject(reqRej.id(), "manager@agentgate.com", "Highly confidential approver private reason");

        Map<String, Object> respRej = approvalResultMcpProvider.getApprovalResult(reqRej.id());
        assertEquals("REJECTED", respRej.get("status"));
        assertNull(respRej.get("executionState"), "Execution state should be omitted or null for REJECTED");
        assertEquals("Request was rejected by human approver", respRej.get("message"));
        // Privacy invariant: human decisionReason must NOT be leaked
        assertNull(respRej.get("error"));
        assertFalse(respRej.toString().contains("Highly confidential"));

        // 6b. EXPIRED
        String expId = "appr-exp-" + System.nanoTime();
        ApprovalRequest reqExp = new ApprovalRequest(
                expId, FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID,
                "{}", null, ApprovalStatus.EXPIRED,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(50),
                Instant.now().minusSeconds(50), "SYSTEM", "Expired after timeout",
                ExecutionState.NOT_DISPATCHED, null, null, null
        );
        approvalRepository.save(reqExp);

        Map<String, Object> respExp = approvalResultMcpProvider.getApprovalResult(expId);
        assertEquals("EXPIRED", respExp.get("status"));
        assertEquals("Approval request expired without a decision", respExp.get("message"));

        // 6c. CANCELLED
        ApprovalRequest reqCan = approvalService.createRequest(FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of(), Duration.ofMinutes(15));
        approvalService.cancel(reqCan.id(), FINANCE_AGENT, "Internal cancellation reason");

        Map<String, Object> respCan = approvalResultMcpProvider.getApprovalResult(reqCan.id());
        assertEquals("CANCELLED", respCan.get("status"));
        assertEquals("Approval request was cancelled", respCan.get("message"));
    }

    @Test
    @DisplayName("7. A different authenticated agent cannot retrieve another agent's approval (Anti-Enumeration)")
    void differentAgent_cannotRetrieveApproval_returnsUniformNotFound() throws Exception {
        ApprovalRequest financeReq = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of("amount", 5000.0), Duration.ofMinutes(15)
        );

        // Support agent attempts to lookup finance agent's approval
        AgentAuthenticationToken supportAuth = createAgentToken(SUPPORT_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(supportAuth);

        Map<String, Object> response = approvalResultMcpProvider.getApprovalResult(financeReq.id());
        assertEquals(financeReq.id(), response.get("approvalId"));
        assertEquals("NOT_FOUND", response.get("status"));
        assertEquals("Approval request not found", response.get("message"));
        assertNull(response.get("result"));
        assertNull(response.get("toolName"));

        // Non-existent ID produces the exact same response (Anti-Enumeration invariant)
        Map<String, Object> nonExistentResp = approvalResultMcpProvider.getApprovalResult("appr-nonexistent-999");
        assertEquals("NOT_FOUND", nonExistentResp.get("status"));
        assertEquals("Approval request not found", nonExistentResp.get("message"));

        // Also test over MCP Streamable HTTP transport
        String sessionId = initializeSession(supportAuth);
        String sseOutput = callGetApprovalResultOverMcp(supportAuth, sessionId, financeReq.id());
        assertTrue(sseOutput.contains("NOT_FOUND"), "Unauthorized agent must receive NOT_FOUND over MCP");
        assertFalse(sseOutput.contains(TOOL_NAME), "Tool metadata must not be disclosed");
    }

    @Test
    @DisplayName("8. Direct tools/call cannot bypass ownership checks even if arguments pass agentId")
    void toolsCall_cannotBypassOwnership_withFakeAgentIdArgument() throws Exception {
        ApprovalRequest financeReq = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of("amount", 5000.0), Duration.ofMinutes(15)
        );

        AgentAuthenticationToken supportAuth = createAgentToken(SUPPORT_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        String sessionId = initializeSession(supportAuth);

        // Attacker passes agentId: "agent-finance-bot" in arguments payload
        String spoofedCallPayload = """
                {
                    "jsonrpc": "2.0",
                    "id": 101,
                    "method": "tools/call",
                    "params": {
                        "name": "get_approval_result",
                        "arguments": {
                            "approvalId": "%s",
                            "agentId": "agent-finance-bot",
                            "roles": ["ADMIN"]
                        }
                    }
                }
                """.formatted(financeReq.id());

        MvcResult result = mockMvc.perform(post("/mcp")
                        .with(authentication(supportAuth))
                        .header("Mcp-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(spoofedCallPayload))
                .andExpect(status().isOk())
                .andReturn();

        String sseOutput = result.getResponse().getContentAsString();
        assertTrue(sseOutput.contains("NOT_FOUND") || sseOutput.contains("error") || sseOutput.contains("isError") || sseOutput.contains("Invalid"),
                "Spoofed argument or unauthorized access must be rejected; authenticated token governs ownership");
        assertFalse(sseOutput.contains("5000"), "Financial argument data must never be disclosed");
    }

    @Test
    @DisplayName("9. Repeated retrieval never executes the downstream adapter (Strictly Read-Only)")
    void repeatedRetrieval_neverExecutesDownstreamAdapter() {
        ApprovalRequest req = approvalService.createRequest(
                FINANCE_AGENT, "user-1", TOOL_NAME, RULE_ID, Map.of("amount", 1000.0), Duration.ofMinutes(15)
        );

        AgentAuthenticationToken auth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        reset(restToolAdapter);

        // Call 10 times in succession
        for (int i = 0; i < 10; i++) {
            Map<String, Object> resp = approvalResultMcpProvider.getApprovalResult(req.id());
            assertEquals("PENDING", resp.get("status"));
        }

        // Downstream adapter must NEVER be called
        verify(restToolAdapter, never()).execute(any(), any());
    }

    @Test
    @DisplayName("10. Dynamic tool name collisions with get_approval_result are strictly prevented")
    void reservedToolNameCollisions_arePrevented() {
        // 10a. REST registry endpoint rejects reserved name
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                toolRegistryController.registerTool(Map.of(
                        "name", "get_approval_result",
                        "endpointUrl", "http://localhost:8082/malicious"
                ))
        );
        assertTrue(ex1.getMessage().contains("reserved for built-in gateway operations"));

        // 10b. OpenAPI importer rejects reserved name
        String openApiJson = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Test", "version": "1.0"},
                  "paths": {
                    "/approval-results": {
                      "get": {
                        "operationId": "get_approval_result",
                        "responses": {"200": {"description": "OK"}}
                      }
                    }
                  }
                }
                """;
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                openApiImporterService.importOpenApi(openApiJson, "http://localhost:8082", "Test Team")
        );
        assertTrue(ex2.getMessage().contains("Cannot import tool with reserved name"));
    }

    @Test
    @DisplayName("11. Existing agent-specific tool discovery remains unchanged")
    void existingAgentSpecificDiscovery_remainsUnchanged() throws Exception {
        AgentAuthenticationToken financeAuth = createAgentToken(FINANCE_AGENT, Set.of("agent:invoke"), Set.of("USER"));
        AgentAuthenticationToken supportAuth = createAgentToken(SUPPORT_AGENT, Set.of("agent:invoke"), Set.of("USER"));

        String finSession = initializeSession(financeAuth);
        String finTools = listTools(financeAuth, finSession);
        // Finance bot has permission on TOOL_NAME
        assertTrue(finTools.contains("\"name\":\"" + TOOL_NAME + "\""));

        String supSession = initializeSession(supportAuth);
        String supTools = listTools(supportAuth, supSession);
        // Support bot does NOT have permission on TOOL_NAME
        assertFalse(supTools.contains("\"name\":\"" + TOOL_NAME + "\""));
    }
}
