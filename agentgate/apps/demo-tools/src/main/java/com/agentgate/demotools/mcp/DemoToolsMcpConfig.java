package com.agentgate.demotools.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoToolsMcpConfig {

    @Bean
    public ToolCallbackProvider demoMcpToolCallbackProvider(DemoMcpInventoryService inventoryService) {
        return ToolCallbackProvider.from(ToolCallbacks.from(inventoryService));
    }
}
