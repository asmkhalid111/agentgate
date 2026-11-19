package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
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
    private ToolExecutionPipeline pipeline;
    private ObjectMapper objectMapper;

    private RegisteredTool refundTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validationGuard = new JsonSchemaValidationGuard(objectMapper);
        pipeline = new ToolExecutionPipeline(toolRepository, List.of(toolAdapter), policyEvaluator, validationGuard);

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
}
