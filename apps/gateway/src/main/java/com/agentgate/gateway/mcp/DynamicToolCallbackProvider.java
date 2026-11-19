package com.agentgate.gateway.mcp;

import com.agentgate.gateway.identity.AgentContextHolder;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class DynamicToolCallbackProvider implements ToolCallbackProvider {

    private final RegisteredToolRepository toolRepository;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;
    private final DemoToolsMcpProvider demoToolsMcpProvider;
    private final ApprovalResultMcpProvider approvalResultMcpProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamicToolCallbackProvider(
            RegisteredToolRepository toolRepository,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            DemoToolsMcpProvider demoToolsMcpProvider,
            ApprovalResultMcpProvider approvalResultMcpProvider) {
        this.toolRepository = toolRepository;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.demoToolsMcpProvider = demoToolsMcpProvider;
        this.approvalResultMcpProvider = approvalResultMcpProvider;
    }

    public DynamicToolCallbackProvider(
            RegisteredToolRepository toolRepository,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            DemoToolsMcpProvider demoToolsMcpProvider) {
        this(toolRepository, pipeline, objectMapper, demoToolsMcpProvider, null);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        String callingAgent = AgentContextHolder.getCurrentAgent()
                .map(AgentIdentity::clientId)
                .orElse(null);

        List<RegisteredTool> tools;
        try {
            if (callingAgent != null && !callingAgent.isBlank()) {
                tools = toolRepository.findPermittedToolsForAgent(callingAgent);
            } else {
                tools = toolRepository.findAll();
            }
        } catch (Exception e) {
            tools = List.of();
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        // Include baseline demo tools for M01 backward compatibility
        callbacks.addAll(Arrays.asList(ToolCallbacks.from(demoToolsMcpProvider)));

        // Include built-in approval result retrieval tool (FR4)
        if (approvalResultMcpProvider != null) {
            callbacks.addAll(Arrays.asList(ToolCallbacks.from(approvalResultMcpProvider)));
        }

        for (RegisteredTool tool : tools) {
            callbacks.add(new DynamicGovernedToolCallback(tool, pipeline, objectMapper));
        }

        return callbacks.toArray(new ToolCallback[0]);
    }
}
