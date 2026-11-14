package com.agentgate.gateway.config;

import com.agentgate.gateway.mcp.DemoToolsMcpProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider demoToolsCallbackProvider(DemoToolsMcpProvider demoToolsMcpProvider) {
        return ToolCallbackProvider.from(ToolCallbacks.from(demoToolsMcpProvider));
    }
}

