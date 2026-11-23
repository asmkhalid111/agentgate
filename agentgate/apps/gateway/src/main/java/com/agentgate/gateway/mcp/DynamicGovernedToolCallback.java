package com.agentgate.gateway.mcp;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.identity.AgentContextHolder;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collections;
import java.util.Map;

/**
 * Dynamic ToolCallback adapter that bridges Spring AI MCP server to the AgentGate execution pipeline.
 */
public class DynamicGovernedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DynamicGovernedToolCallback.class);

    private final RegisteredTool tool;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;
    private final ToolDefinition toolDefinition;

    public DynamicGovernedToolCallback(RegisteredTool tool, ToolExecutionPipeline pipeline, ObjectMapper objectMapper) {
        this.tool = tool;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.toolDefinition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        java.util.Optional<AgentIdentity> currentAgent = AgentContextHolder.getCurrentAgent();
        String callingAgent = currentAgent
                .map(AgentIdentity::clientId)
                .orElse("anonymous");
        java.util.Set<String> agentRoles = currentAgent
                .map(AgentIdentity::roles)
                .orElse(Collections.emptySet());

        log.info("MCP tools/call received for '{}' from agent '{}'", tool.name(), callingAgent);

        Map<String, Object> arguments;
        try {
            if (toolInput != null && !toolInput.isBlank()) {
                arguments = objectMapper.readValue(toolInput, new TypeReference<>() {});
            } else {
                arguments = Collections.emptyMap();
            }
        } catch (Exception e) {
            log.error("Failed to parse tool arguments for '{}': {}", tool.name(), e.getMessage());
            throw new IllegalArgumentException(safeJsonError("Invalid JSON arguments: " + e.getMessage()));
        }

        ToolExecutionContext context = new ToolExecutionContext(
                callingAgent,
                tool.name(),
                arguments,
                null,
                tool.riskLevel(),
                agentRoles
        );

        try {
            ToolExecutionResult result = pipeline.execute(context);
            if (com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext.isTimingEnabled()) {
                attachBenchmarkTimingHeaders();
            }
            if (result.success()) {
                return objectMapper.writeValueAsString(result.data());
            } else {
                log.warn("Governed tool execution rejected for '{}': {}", tool.name(), result.error());
                throw new IllegalArgumentException(safeJsonError(result.error()));
            }
        } catch (SecurityException se) {
            log.warn("Access denied during tools/call: {}", se.getMessage());
            throw new SecurityException(safeJsonError("ACCESS_DENIED: " + se.getMessage()));
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            log.error("Tool execution failed: {}", e.getMessage());
            throw new RuntimeException(safeJsonError(e.getMessage()), e);
        }
    }

    private void attachBenchmarkTimingHeaders() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                jakarta.servlet.http.HttpServletResponse response = servletAttrs.getResponse();
                if (response != null && !response.isCommitted()) {
                    Long startNs = com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext.getGatewayStartNs();
                    if (startNs != null) {
                        long totalNs = System.nanoTime() - startNs;
                        Long downstreamNs = com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext.getDownstreamDurationNs();
                        long downstream = (downstreamNs != null) ? downstreamNs : 0L;
                        long overheadNs = Math.max(0, totalNs - downstream);

                        response.setHeader("X-Gateway-Duration-Ns", String.valueOf(totalNs));
                        response.setHeader("X-Downstream-Duration-Ns", String.valueOf(downstream));
                        response.setHeader("X-Gateway-Overhead-Ns", String.valueOf(overheadNs));
                        String corr = com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext.getCorrelationId();
                        if (corr != null) {
                            response.setHeader("X-Request-Correlation-Id", corr);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String safeJsonError(String errorMessage) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", errorMessage != null ? errorMessage : "Execution failed"));
        } catch (Exception e) {
            String escaped = errorMessage != null ? errorMessage.replace("\"", "\\\"").replace("\n", "\\n") : "Execution failed";
            return "{\"error\":\"" + escaped + "\"}";
        }
    }
}
