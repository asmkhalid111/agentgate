package com.agentgate.testsupport;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone embedded downstream MCP server configuration for integration tests.
 * Placed outside {@code com.agentgate.gateway} so {@code GatewayApplication}
 * component scanning and {@code @SpringBootTest} discovery do not pick it up.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
public class DownstreamMcpTestServerApp {

    @Bean
    public SecurityFilterChain downstreamPermitAllSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    public WebMvcStreamableServerTransportProvider downstreamTransport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> downstreamMcpRouter(WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    public McpSyncServer downstreamMcpServer(WebMvcStreamableServerTransportProvider transport) {
        McpSchema.Tool inventoryTool = McpSchema.Tool.builder()
                .name("inventory_check")
                .description("Check warehouse inventory availability for a product SKU")
                .inputSchema(new JacksonMcpJsonMapper(JsonMapper.builder().build()),
                        "{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}},\"required\":[\"sku\"]}")
                .build();

        return McpServer.sync(transport)
                .serverInfo("test-downstream-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(inventoryTool, (exchange, req) -> {
                    Object sku = req.arguments() != null ? req.arguments().get("sku") : "UNKNOWN";
                    String json = "{\"sku\":\"" + sku + "\",\"available\":true,\"quantity\":42,\"warehouse\":\"DHAKA-HUB-01\"}";
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .isError(false)
                            .build();
                })
                .build();
    }
}
