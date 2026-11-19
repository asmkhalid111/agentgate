package com.agentgate.gateway.resilience;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.pipeline.*;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.ratelimit.RateLimitDecision;
import com.agentgate.gateway.ratelimit.RateLimiter;
import com.agentgate.gateway.ratelimit.WriteCapDecision;
import com.agentgate.gateway.ratelimit.WriteCapLimiter;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.resilience.circuitbreaker.CircuitBreakerState;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreaker;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreakerRegistry;
import com.agentgate.gateway.resilience.config.ResilienceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Tool Resilience Pipeline Integration Tests (FR10)")
class ToolResiliencePipelineIntegrationTest {

    @Mock
    private RegisteredToolRepository toolRepository;

    @Mock
    private ToolAdapter toolAdapter;

    @Mock
    private StructuredPolicyEvaluator policyEvaluator;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private ApprovalRequestRepository approvalRepository;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private WriteCapLimiter writeCapLimiter;

    @Mock
    private AuditRecorder auditRecorder;

    private ObjectMapper objectMapper;
    private JsonSchemaValidationGuard validationGuard;
    private PiiRedactionGuard piiRedactionGuard;
    private ResponseTruncationGuard responseTruncationGuard;

    private MutableClock clock;
    private ResilienceProperties resilienceProperties;
    private ToolCircuitBreakerRegistry circuitBreakerRegistry;
    private ToolExecutionPipeline pipeline;
    private ApprovedToolExecutionService approvedService;

    private RegisteredTool mutatingTool;
    private RegisteredTool readOnlyTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validationGuard = new JsonSchemaValidationGuard(objectMapper);
        piiRedactionGuard = new PiiRedactionGuard(objectMapper);
        responseTruncationGuard = new ResponseTruncationGuard(objectMapper);

        clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        resilienceProperties = new ResilienceProperties();
        resilienceProperties.setDefaultFailureThreshold(3);
        resilienceProperties.setDefaultOpenWaitDuration(Duration.ofSeconds(30));

        circuitBreakerRegistry = new ToolCircuitBreakerRegistry(resilienceProperties, clock);

        pipeline = new ToolExecutionPipeline(
                toolRepository,
                List.of(toolAdapter),
                policyEvaluator,
                validationGuard,
                piiRedactionGuard,
                responseTruncationGuard,
                approvalService,
                rateLimiter,
                writeCapLimiter,
                null,
                auditRecorder,
                circuitBreakerRegistry,
                900L
        );

        approvedService = new ApprovedToolExecutionService(
                approvalRepository,
                toolRepository,
                List.of(toolAdapter),
                policyEvaluator,
                validationGuard,
                piiRedactionGuard,
                responseTruncationGuard,
                objectMapper,
                writeCapLimiter,
                null,
                auditRecorder,
                circuitBreakerRegistry
        );

        mutatingTool = new RegisteredTool(
                "tool-refund",
                "orders_refund",
                "Refund tool",
                "{}",
                RiskLevel.HIGH,
                "Finance",
                "http://localhost:8082/api/orders/refund",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );

        readOnlyTool = new RegisteredTool(
                "tool-get",
                "orders_get",
                "Get tool",
                "{}",
                RiskLevel.LOW,
                "Finance",
                "http://localhost:8082/api/orders/{id}",
                ProtocolType.REST,
                "GET",
                true,
                Instant.now(),
                Instant.now()
        );

        lenient().when(toolAdapter.supports(ProtocolType.REST)).thenReturn(true);
    }

    @Test
    @DisplayName("Direct execution: Fast-rejects when circuit is OPEN, does not burn write tokens, does not log write intent")
    void directExecution_openCircuit_fastRejection() {
        when(toolRepository.findByName("orders_refund")).thenReturn(Optional.of(mutatingTool));
        when(toolRepository.isAgentPermitted("orders_refund", "finance-agent")).thenReturn(true);
        when(rateLimiter.tryAcquire("finance-agent", "orders_refund")).thenReturn(RateLimitDecision.allow());
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowFinance", "Allowed", false, 1L));

        // Trip the circuit breaker for orders_refund
        ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker("orders_refund");
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        ToolExecutionContext ctx = new ToolExecutionContext(
                "finance-agent",
                "orders_refund",
                Map.of("orderId", "ord-1"),
                "admin-user",
                RiskLevel.HIGH,
                Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);

        assertFalse(result.success());
        assertTrue(result.error().contains("TOOL_TEMPORARILY_UNAVAILABLE"));

        // CRITICAL INVARIANTS:
        // 1. Adapter must NEVER be invoked
        verify(toolAdapter, never()).execute(any(), any());

        // 2. Write cap tokens must NOT be acquired/deducted
        verify(writeCapLimiter, never()).tryAcquireWrite(any());

        // 3. Pre-dispatch write intent must NOT be recorded
        verify(auditRecorder, never()).recordWriteDispatchIntent(any(), any(), any(), any(), any(), any(), any());

        // 4. Governance denial audit must be recorded with CIRCUIT_BREAKER_OPEN
        verify(auditRecorder, times(1)).recordGovernanceDenial(
                eq("finance-agent"),
                eq("admin-user"),
                eq("orders_refund"),
                any(),
                eq("CIRCUIT_BREAKER_OPEN"),
                eq("R1"),
                eq("AllowFinance"),
                contains("TOOL_TEMPORARILY_UNAVAILABLE"),
                anyLong()
        );
    }

    @Test
    @DisplayName("Direct execution: 3 consecutive 5xx downstream errors trip the circuit breaker")
    void directExecution_three5xxErrors_tripsBreaker() {
        when(toolRepository.findByName("orders_get")).thenReturn(Optional.of(readOnlyTool));
        when(toolRepository.isAgentPermitted("orders_get", "finance-agent")).thenReturn(true);
        when(rateLimiter.tryAcquire("finance-agent", "orders_get")).thenReturn(RateLimitDecision.allow());
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowFinance", "Allowed", false, 1L));

        HttpServerErrorException err500 = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, new byte[0], null);
        when(toolAdapter.execute(any(), any())).thenReturn(ToolExecutionResult.failure("500 Internal Server Error", 50L, err500, 500));

        ToolExecutionContext ctx = new ToolExecutionContext(
                "finance-agent",
                "orders_get",
                Map.of(),
                "admin-user",
                RiskLevel.LOW,
                Set.of()
        );

        ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker("orders_get");
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());

        pipeline.execute(ctx);
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());

        pipeline.execute(ctx);
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());

        pipeline.execute(ctx);
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        // 4th call should now be fast-rejected without invoking adapter
        pipeline.execute(ctx);
        verify(toolAdapter, times(3)).execute(any(), any());
    }

    @Test
    @DisplayName("Direct execution: Downstream 4xx client errors do NOT count toward circuit breaker failure")
    void directExecution_4xxErrors_doNotTripBreaker() {
        when(toolRepository.findByName("orders_get")).thenReturn(Optional.of(readOnlyTool));
        when(toolRepository.isAgentPermitted("orders_get", "finance-agent")).thenReturn(true);
        when(rateLimiter.tryAcquire("finance-agent", "orders_get")).thenReturn(RateLimitDecision.allow());
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowFinance", "Allowed", false, 1L));

        HttpClientErrorException err404 = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, new byte[0], null);
        when(toolAdapter.execute(any(), any())).thenReturn(ToolExecutionResult.failure("404 Not Found", 20L, err404, 404));

        ToolExecutionContext ctx = new ToolExecutionContext(
                "finance-agent",
                "orders_get",
                Map.of(),
                "admin-user",
                RiskLevel.LOW,
                Set.of()
        );

        ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker("orders_get");

        // 5 consecutive 404s
        for (int i = 0; i < 5; i++) {
            pipeline.execute(ctx);
        }

        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getConsecutiveFailures());
        verify(toolAdapter, times(5)).execute(any(), any());
    }

    @Test
    @DisplayName("Approved execution: Fast-rejects when circuit is OPEN, does not burn write tokens, records failure")
    void approvedExecution_openCircuit_fastRejection() {
        String approvalId = "app-test-99";
        ApprovalRequest request = new ApprovalRequest(
                approvalId,
                "finance-agent",
                "admin-user",
                "orders_refund",
                "R1",
                "{\"orderId\":\"ord-1\"}",
                null,
                com.agentgate.gateway.approval.domain.ApprovalStatus.APPROVED,
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(600),
                Instant.now(),
                "human-approver",
                null
        );

        when(approvalRepository.claimExecution(eq(approvalId), any())).thenReturn(1);
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(request));
        when(toolRepository.findByName("orders_refund")).thenReturn(Optional.of(mutatingTool));
        when(toolRepository.isAgentPermitted("orders_refund", "finance-agent")).thenReturn(true);
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowFinance", "Allowed", false, 1L));

        // Trip breaker
        ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker("orders_refund");
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        approvedService.executeApproved(approvalId);

        // Verify adapter was NEVER called
        verify(toolAdapter, never()).execute(any(), any());

        // Verify write cap tokens were NOT acquired
        verify(writeCapLimiter, never()).tryAcquireWrite(any());

        // Verify pre-dispatch write intent was NOT recorded
        verify(auditRecorder, never()).recordWriteDispatchIntent(any(), any(), any(), any(), any(), any(), any());

        // Verify approval was recorded as failure with TOOL_TEMPORARILY_UNAVAILABLE
        verify(approvalRepository, times(1)).recordExecutionFailure(
                eq(approvalId),
                contains("TOOL_TEMPORARILY_UNAVAILABLE"),
                any()
        );

        // Verify audit logged approved execution failure
        verify(auditRecorder, times(1)).recordApprovedExecution(
                eq(approvalId),
                eq("finance-agent"),
                eq("admin-user"),
                eq("orders_refund"),
                any(),
                eq("R1"),
                eq("human-approver"),
                eq("FAILED"),
                eq(0L),
                contains("TOOL_TEMPORARILY_UNAVAILABLE")
        );
    }

    @Test
    @DisplayName("Half-open recovery in pipeline: Successful trial recovers breaker from OPEN to CLOSED")
    void halfOpenRecovery_inPipeline() {
        when(toolRepository.findByName("orders_get")).thenReturn(Optional.of(readOnlyTool));
        when(toolRepository.isAgentPermitted("orders_get", "finance-agent")).thenReturn(true);
        when(rateLimiter.tryAcquire("finance-agent", "orders_get")).thenReturn(RateLimitDecision.allow());
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowFinance", "Allowed", false, 1L));

        // Trip breaker
        ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker("orders_get");
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        // Advance past 30s open wait duration
        clock.advance(Duration.ofSeconds(31));

        // Downstream tool is healthy now
        when(toolAdapter.execute(any(), any())).thenReturn(ToolExecutionResult.success(Map.of("status", "ok"), 10L));

        ToolExecutionContext ctx = new ToolExecutionContext(
                "finance-agent",
                "orders_get",
                Map.of(),
                "admin-user",
                RiskLevel.LOW,
                Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertTrue(result.success());

        // State must now be recovered to CLOSED
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getConsecutiveFailures());
    }

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        public MutableClock(Instant initial) {
            this.current = initial;
        }

        public void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
