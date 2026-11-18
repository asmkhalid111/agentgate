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
        String callingAgent = AgentContextHolder.getCurrentAgent()
                .map(AgentIdentity::clientId)
                .orElse("anonymous");

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
            return "{\"error\":\"Invalid JSON arguments: " + e.getMessage() + "\"}";
        }

        ToolExecutionContext context = new ToolExecutionContext(
                callingAgent,
                tool.name(),
                arguments,
                null,
                tool.riskLevel()
        );

        try {
            ToolExecutionResult result = pipeline.execute(context);
            if (result.success()) {
                return objectMapper.writeValueAsString(result.data());
            } else {
                return "{\"error\":\"" + result.error() + "\"}";
            }
        } catch (SecurityException se) {
            log.warn("Access denied during tools/call: {}", se.getMessage());
            return "{\"error\":\"ACCESS_DENIED: " + se.getMessage() + "\"}";
        } catch (Exception e) {
            log.error("Tool execution failed: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
