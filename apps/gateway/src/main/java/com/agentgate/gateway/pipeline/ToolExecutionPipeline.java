package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);
    private final RegisteredToolRepository toolRepository;
    private final List<ToolAdapter> adapters;

    public ToolExecutionPipeline(RegisteredToolRepository toolRepository, List<ToolAdapter> adapters) {
        this.toolRepository = toolRepository;
        this.adapters = adapters;
    }

    /**
     * Executes a tool through the shared governance pipeline.
     * Enforces tool status and direct agent invocation authorization.
     */
    public ToolExecutionResult execute(ToolExecutionContext context) {
        RegisteredTool tool = toolRepository.findByName(context.toolName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + context.toolName()));

        if (!tool.enabled()) {
            log.warn("Blocked call to disabled tool '{}' from agent '{}'", context.toolName(), context.agentId());
            throw new IllegalStateException("Tool '" + context.toolName() + "' is currently disabled");
        }

        // Direct invocation authorization check (Mandatory Reviewer Amendment 4)
        if (!toolRepository.isAgentPermitted(context.toolName(), context.agentId())) {
            log.warn("Security violation: Agent '{}' unauthorized for tool '{}'", context.agentId(), context.toolName());
            throw new SecurityException("Agent '" + context.agentId() + "' is not authorized to invoke tool '" + context.toolName() + "'");
        }

        ToolAdapter adapter = adapters.stream()
                .filter(a -> a.supports(tool.protocolType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported protocol: " + tool.protocolType()));

        ToolExecutionContext enrichedContext = context.withRiskLevel(tool.riskLevel());
        return adapter.execute(tool, enrichedContext);
    }
}
