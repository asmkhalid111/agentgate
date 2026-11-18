package com.agentgate.gateway.config;

import com.agentgate.gateway.mcp.DynamicToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class McpConfig {

    @Bean
    @Primary
    public ToolCallbackProvider mcpToolCallbackProvider(DynamicToolCallbackProvider dynamicProvider) {
        return dynamicProvider;
    }
}


