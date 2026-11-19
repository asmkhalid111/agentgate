package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.mcp.DynamicGovernedToolCallback;
import com.agentgate.gateway.policy.domain.PolicyDecision;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionPipeline Validation Integration Test (FR5)")
class ToolExecutionPipelineValidationTest {

    @Mock
    private RegisteredToolRepository toolRepository;

    @Mock
    private ToolAdapter toolAdapter;

    @Mock
    private StructuredPolicyEvaluator policyEvaluator;

    private JsonSchemaValidationGuard validationGuard;
    private PiiRedactionGuard piiRedactionGuard;
    private ResponseTruncationGuard responseTruncationGuard;
    private ToolExecutionPipeline pipeline;
    private ObjectMapper objectMapper;

    private RegisteredTool refundTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validationGuard = new JsonSchemaValidationGuard(objectMapper);
        piiRedactionGuard = new PiiRedactionGuard(objectMapper);
        responseTruncationGuard = new ResponseTruncationGuard(objectMapper);
        pipeline = new ToolExecutionPipeline(toolRepository, List.of(toolAdapter), policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard);

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" },
                    "amount": { "type": "number" }
                  },
                  "required": ["orderId", "amount"]
                }
                """;

        refundTool = new RegisteredTool(
                "tool-orders-refund",
                "orders_refund",
                "Refund tool",
                schema,
                RiskLevel.HIGH,
                "Finance Team",
                "http://localhost:8082/api/orders/refund",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );

        when(toolRepository.findByName("orders_refund")).thenReturn(Optional.of(refundTool));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("7. Invalid arguments must be rejected by pipeline and NEVER reach downstream adapter")
    void pipeline_invalidArguments_neverReachDownstreamAdapter() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);

        // Missing required property 'amount'
        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999"),
                null,
                RiskLevel.HIGH
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertFalse(result.success(), "Expected execution failure due to schema violation");
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("SCHEMA_VALIDATION_FAILED:"), "Error must have SCHEMA_VALIDATION_FAILED prefix");
        assertTrue(result.error().contains("amount"), "Error should report missing 'amount'");

        // Downstream adapter must NEVER be invoked
        verify(toolAdapter, never()).execute(any(), any());
        // Policy evaluator must also be bypassed
        verify(policyEvaluator, never()).evaluate(any());
    }

    @Test
    @DisplayName("8. Valid arguments must pass validation and proceed through policy and adapter execution")
    void pipeline_validArguments_proceedToPolicyAndAdapter() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(Map.of("status", "SUCCESS"), 5L));

        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success(), "Expected valid execution to succeed");
        assertNull(result.error());
        assertNotNull(result.data());

        // Policy evaluator and downstream adapter must be invoked
        verify(policyEvaluator, times(1)).evaluate(any());
        verify(toolAdapter, times(1)).execute(eq(refundTool), any());
    }

    @Test
    @DisplayName("9. MCP DynamicGovernedToolCallback throws exception on validation failure for isError: true")
    void mcpCallback_validationFailure_throwsExceptionForIsErrorTrue() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        AgentIdentity identity = new AgentIdentity("agent-finance-bot", Set.of("agent:invoke"), Set.of("ROLE_AGENT"));
        SecurityContextHolder.getContext().setAuthentication(new AgentAuthenticationToken(identity, "token", List.of()));

        DynamicGovernedToolCallback callback = new DynamicGovernedToolCallback(refundTool, pipeline, objectMapper);

        // Invalid JSON arguments (string for amount instead of number)
        String invalidInput = "{\"orderId\":\"ORD-123\",\"amount\":\"not-a-number\"}";

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> callback.call(invalidInput));
        assertTrue(thrown.getMessage().contains("SCHEMA_VALIDATION_FAILED"), "Exception message should report schema validation failure");

        // Verify downstream adapter was not executed
        verify(toolAdapter, never()).execute(any(), any());
    }

    @Test
    @DisplayName("10. Pipeline redacts sensitive PII in downstream tool response for standard-clearance agent")
    void pipeline_standardClearance_redactsSensitivePiiInOutput() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        Map<String, Object> downstreamData = Map.of(
                "orderId", "ORD-999",
                "customerEmail", "customer@agentgate.com",
                "customerPhone", "01712345678",
                "nid", "1234567890",
                "amount", 1500.0
        );
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(downstreamData, 10L));

        // Standard clearance agent (ROLE_AGENT, no HIGH_CLEARANCE)
        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_AGENT")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.data();
        assertEquals("ORD-999", output.get("orderId"));
        assertEquals(1500.0, output.get("amount"));
        assertEquals("[REDACTED_EMAIL]", output.get("customerEmail"));
        assertEquals("[REDACTED_PHONE]", output.get("customerPhone"));
        assertEquals("[REDACTED_NID]", output.get("nid"));
    }

    @Test
    @DisplayName("11. Pipeline preserves raw unredacted data for explicit HIGH_CLEARANCE agent")
    void pipeline_highClearance_bypassesPiiRedaction() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        Map<String, Object> downstreamData = Map.of(
                "customerEmail", "ceo@agentgate.com",
                "customerPhone", "+8801812345678",
                "nid", "19901234567890123"
        );
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(downstreamData, 10L));

        // Explicit HIGH_CLEARANCE agent
        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_HIGH_CLEARANCE")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.data();
        assertEquals("ceo@agentgate.com", output.get("customerEmail"));
        assertEquals("+8801812345678", output.get("customerPhone"));
        assertEquals("19901234567890123", output.get("nid"));
    }

    @Test
    @DisplayName("12. Downstream error messages leaking PII are sanitized for standard-clearance agent (Correction D)")
    void pipeline_downstreamErrorLeakingPii_sanitizedForStandardAgent() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        String rawError = "Downstream failure for customer bob@example.com (01712345678, NID: 1234567890): system timeout";
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.failure(rawError, 15L));

        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_AGENT")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertFalse(result.success());
        assertEquals("Downstream failure for customer [REDACTED_EMAIL] ([REDACTED_PHONE], NID: [REDACTED_NID]): system timeout", result.error());
    }

    @Test
    @DisplayName("13. Pipeline truncates oversized downstream output with explicit truncation notice (FR5)")
    @SuppressWarnings("unchecked")
    void pipeline_oversizedOutput_truncatedWithExplicitNotice() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        List<Map<String, Object>> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            largeList.add(Map.of("id", "row-" + i, "status", "SUCCESS"));
        }
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(largeList, 20L));

        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_AGENT")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success());
        List<Object> truncatedList = (List<Object>) result.data();
        assertEquals(100, truncatedList.size(), "Oversized array must be truncated to strictly 100 items");

        Map<String, Object> noticeItem = (Map<String, Object>) truncatedList.get(99);
        assertTrue(noticeItem.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
        assertTrue(String.valueOf(noticeItem.get(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY)).contains("100 items"));
    }

    @Test
    @DisplayName("14. Pipeline applies PII redaction first, and then truncates oversized response (Guards Order)")
    @SuppressWarnings("unchecked")
    void pipeline_oversizedOutputWithPii_redactsPiiFirstThenTruncates() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        List<Map<String, Object>> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 130; i++) {
            largeList.add(Map.of(
                    "id", "user-" + i,
                    "email", "user" + i + "@example.com",
                    "phone", "01712345678"
            ));
        }
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(largeList, 25L));

        // Standard clearance agent
        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_AGENT")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success());
        List<Object> truncatedList = (List<Object>) result.data();
        assertEquals(100, truncatedList.size());

        // First item must have PII redacted
        Map<String, Object> firstItem = (Map<String, Object>) truncatedList.get(0);
        assertEquals("[REDACTED_EMAIL]", firstItem.get("email"));
        assertEquals("[REDACTED_PHONE]", firstItem.get("phone"));

        // 100th item is the truncation notice
        Map<String, Object> lastItem = (Map<String, Object>) truncatedList.get(99);
        assertTrue(lastItem.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }

    @Test
    @DisplayName("15. HIGH_CLEARANCE agent output bypasses PII redaction but is subject to response truncation (Correction C)")
    @SuppressWarnings("unchecked")
    void pipeline_highClearance_doesNotRedactPii_butDoesTruncateOversizedOutput() {
        when(toolRepository.isAgentPermitted("orders_refund", "agent-finance-bot")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("rule-1", "Allow Rule", "Allowed by policy", false, 1L));
        when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);

        List<Map<String, Object>> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            largeList.add(Map.of(
                    "id", "admin-row-" + i,
                    "email", "admin" + i + "@agentgate.com"
            ));
        }
        when(toolAdapter.execute(eq(refundTool), any())).thenReturn(ToolExecutionResult.success(largeList, 25L));

        // High clearance agent
        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-999", "amount", 1500.0),
                null,
                RiskLevel.HIGH,
                Set.of("ROLE_HIGH_CLEARANCE")
        );

        ToolExecutionResult result = pipeline.execute(context);

        assertTrue(result.success());
        List<Object> truncatedList = (List<Object>) result.data();
        assertEquals(100, truncatedList.size(), "High-clearance agent must still be truncated to 100 items");

        // High-clearance agent sees raw unredacted email
        Map<String, Object> firstItem = (Map<String, Object>) truncatedList.get(0);
        assertEquals("admin0@agentgate.com", firstItem.get("email"), "High-clearance sees raw unredacted email");

        // But 100th item is still the truncation notice
        Map<String, Object> lastItem = (Map<String, Object>) truncatedList.get(99);
        assertTrue(lastItem.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }
}
