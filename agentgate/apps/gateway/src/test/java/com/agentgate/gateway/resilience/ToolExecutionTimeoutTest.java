package com.agentgate.gateway.resilience;

import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.PolicyAction;
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
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreakerRegistry;
import com.agentgate.gateway.resilience.config.ResilienceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Downstream Tool Execution Timeout & Indeterminate Handling Tests (FR10)")
class ToolExecutionTimeoutTest {

    private static HttpServer slowServer;
    private static int serverPort;

    @BeforeAll
    static void startServer() throws Exception {
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPort = slowServer.getAddress().getPort();

        slowServer.createContext("/api/slow", exchange -> {
            try {
                // Sleep longer than the client read timeout (400ms)
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            byte[] response = "{\"status\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        slowServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (slowServer != null) {
            slowServer.stop(0);
        }
    }

    @Test
    @DisplayName("RestToolAdapter aborts cleanly at configured read timeout when downstream hangs")
    void restToolAdapter_abortsAtReadTimeout() {
        ResilienceProperties properties = new ResilienceProperties();
        ResilienceProperties.ToolResilienceConfig config = new ResilienceProperties.ToolResilienceConfig();
        config.setConnectTimeout(Duration.ofMillis(500));
        config.setReadTimeout(Duration.ofMillis(400));
        properties.setTools(Map.of("slow_tool", config));

        RestToolAdapter adapter = new RestToolAdapter(new ObjectMapper(), properties);

        RegisteredTool tool = new RegisteredTool(
                "tool-slow",
                "slow_tool",
                "Slow Tool",
                "{}",
                RiskLevel.MEDIUM,
                "Ops",
                "http://127.0.0.1:" + serverPort + "/api/slow",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-agent",
                "slow_tool",
                Map.of(),
                "test-user",
                RiskLevel.MEDIUM,
                Set.of()
        );

        long start = System.currentTimeMillis();
        ToolExecutionResult result = adapter.execute(tool, ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.success());
        assertNotNull(result.error());
        String errLower = result.error().toLowerCase();
        boolean timedOut = errLower.contains("timed out") || errLower.contains("timeout") || errLower.contains("cancelled");
        for (Throwable cause = result.cause(); cause != null; cause = cause.getCause()) {
            timedOut |= cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.util.concurrent.CancellationException;
        }
        assertTrue(timedOut, () -> "Expected a downstream timeout, got " + result.cause());
        // Aborted around 400ms, definitely well before 1500ms
        assertTrue(elapsed < 1400, "Adapter should have timed out before server finished (elapsed: " + elapsed + "ms)");
    }

    @Test
    @DisplayName("Mutating tool timeout in pipeline is classified INDETERMINATE, preserves write intent, and is never retried")
    void mutatingToolTimeout_classifiedIndeterminate_neverRetried() {
        ResilienceProperties properties = new ResilienceProperties();
        ResilienceProperties.ToolResilienceConfig config = new ResilienceProperties.ToolResilienceConfig();
        config.setConnectTimeout(Duration.ofMillis(500));
        config.setReadTimeout(Duration.ofMillis(400));
        properties.setTools(Map.of("slow_write_tool", config));

        ObjectMapper objectMapper = new ObjectMapper();
        RestToolAdapter realAdapter = new RestToolAdapter(objectMapper, properties);
        RestToolAdapter spyAdapter = spy(realAdapter);

        RegisteredToolRepository toolRepository = mock(RegisteredToolRepository.class);
        StructuredPolicyEvaluator policyEvaluator = mock(StructuredPolicyEvaluator.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        WriteCapLimiter writeCapLimiter = mock(WriteCapLimiter.class);
        AuditRecorder auditRecorder = mock(AuditRecorder.class);
        ToolCircuitBreakerRegistry circuitBreakerRegistry = new ToolCircuitBreakerRegistry(properties);

        RegisteredTool mutatingTool = new RegisteredTool(
                "tool-slow-write",
                "slow_write_tool",
                "Slow Mutating Tool",
                "{}",
                RiskLevel.HIGH,
                "Ops",
                "http://127.0.0.1:" + serverPort + "/api/slow",
                ProtocolType.REST,
                "POST", // Mutating write operation
                true,
                Instant.now(),
                Instant.now()
        );

        when(toolRepository.findByName("slow_write_tool")).thenReturn(Optional.of(mutatingTool));
        when(toolRepository.isAgentPermitted("slow_write_tool", "test-agent")).thenReturn(true);
        when(rateLimiter.tryAcquire("test-agent", "slow_write_tool")).thenReturn(RateLimitDecision.allow());
        when(writeCapLimiter.tryAcquireWrite("test-agent")).thenReturn(WriteCapDecision.allow());
        when(policyEvaluator.evaluate(any())).thenReturn(PolicyDecision.allow("R1", "AllowSlow", "Allowed", false, 1L));

        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                toolRepository,
                List.of(spyAdapter),
                policyEvaluator,
                new JsonSchemaValidationGuard(objectMapper),
                new PiiRedactionGuard(objectMapper),
                new ResponseTruncationGuard(objectMapper),
                approvalService,
                rateLimiter,
                writeCapLimiter,
                null,
                auditRecorder,
                circuitBreakerRegistry,
                900L
        );

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-agent",
                "slow_write_tool",
                Map.of(),
                "test-user",
                RiskLevel.HIGH,
                Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);

        assertFalse(result.success());
        String pipelineErrLower = result.error().toLowerCase();
        assertTrue(pipelineErrLower.contains("timed out") || pipelineErrLower.contains("timeout") || pipelineErrLower.contains("cancelled"));

        // Verify downstream adapter was invoked exactly ONCE (never retried)
        verify(spyAdapter, times(1)).execute(eq(mutatingTool), any());

        // Verify pre-dispatch write intent was recorded
        verify(auditRecorder, times(1)).recordWriteDispatchIntent(
                eq("test-agent"),
                eq("test-user"),
                eq("slow_write_tool"),
                any(),
                any(),
                any(),
                isNull()
        );

        // Verify execution audit recorded outcome as INDETERMINATE
        verify(auditRecorder, times(1)).recordDirectToolExecution(
                eq("test-agent"),
                eq("test-user"),
                eq("slow_write_tool"),
                any(),
                any(),
                any(),
                eq("ALLOW"),
                anyLong(),
                eq("INDETERMINATE"),
                argThat(msg -> msg != null && (msg.toLowerCase().contains("timed out") || msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("cancelled")))
        );
    }
}
