package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adapter for executing tools on downstream MCP servers.
 */
@Component
public class McpDownstreamToolAdapter implements ToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpDownstreamToolAdapter.class);

    @Override
    public boolean supports(ProtocolType protocolType) {
        return protocolType == ProtocolType.MCP;
    }

    @Override
    public ToolExecutionResult execute(RegisteredTool tool, ToolExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("Executing downstream MCP tool '{}' on endpoint '{}'", tool.name(), tool.endpointUrl());
            // Simulated downstream MCP invocation payload for registered MCP downstream tools
            Map<String, Object> responseData = Map.of(
                    "status", "COMPLETED",
                    "tool", tool.name(),
                    "protocol", "MCP",
                    "result", context.arguments()
            );
            long latency = System.currentTimeMillis() - startTime;
            return ToolExecutionResult.success(responseData, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Downstream MCP tool execution failed: {}", e.getMessage());
            return ToolExecutionResult.failure("Downstream MCP error: " + e.getMessage(), latency);
        }
    }
}
