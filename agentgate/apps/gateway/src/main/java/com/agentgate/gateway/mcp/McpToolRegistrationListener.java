package com.agentgate.gateway.mcp;

import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.event.ToolDeletedEvent;
import com.agentgate.gateway.registry.event.ToolRegisteredEvent;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpToolRegistrationListener {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrationListener.class);

    private final ObjectProvider<McpSyncServer> mcpSyncServerProvider;
    private final RegisteredToolRepository toolRepository;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;

    public McpToolRegistrationListener(
            ObjectProvider<McpSyncServer> mcpSyncServerProvider,
            RegisteredToolRepository toolRepository,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper) {
        this.mcpSyncServerProvider = mcpSyncServerProvider;
        this.toolRepository = toolRepository;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        mcpSyncServerProvider.ifAvailable(server -> {
            try {
                List<RegisteredTool> tools = toolRepository.findAll();
                log.info("Registering {} pre-existing database tools to McpSyncServer", tools.size());
                for (RegisteredTool tool : tools) {
                    registerToolWithServer(server, tool);
                }
            } catch (Exception e) {
                log.warn("Could not register pre-existing tools on McpSyncServer: {}", e.getMessage());
            }
        });
    }

    @EventListener
    public void onToolRegistered(ToolRegisteredEvent event) {
        mcpSyncServerProvider.ifAvailable(server -> {
            try {
                registerToolWithServer(server, event.tool());
                server.notifyToolsListChanged();
                log.info("Dynamically registered tool '{}' to McpSyncServer and sent notifyToolsListChanged", event.tool().name());
            } catch (Exception e) {
                log.error("Failed to dynamically register tool '{}' on McpSyncServer: {}", event.tool().name(), e.getMessage());
            }
        });
    }

    @EventListener
    public void onToolDeleted(ToolDeletedEvent event) {
        mcpSyncServerProvider.ifAvailable(server -> {
            try {
                server.removeTool(event.toolName());
                server.notifyToolsListChanged();
                log.info("Removed tool '{}' from McpSyncServer", event.toolName());
            } catch (Exception e) {
                log.error("Failed to remove tool '{}' from McpSyncServer: {}", event.toolName(), e.getMessage());
            }
        });
    }

    private void registerToolWithServer(McpSyncServer server, RegisteredTool tool) {
        DynamicGovernedToolCallback callback = new DynamicGovernedToolCallback(tool, pipeline, objectMapper);
        var spec = McpToolUtils.toSyncToolSpecification(callback, null);
        server.addTool(spec);
    }
}
