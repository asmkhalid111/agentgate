package com.agentgate.gateway.config;

import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.mcp.DynamicToolCallbackProvider;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.Map;

@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Bean
    @Primary
    public ToolCallbackProvider mcpToolCallbackProvider(DynamicToolCallbackProvider dynamicProvider) {
        return dynamicProvider;
    }

    /**
     * Configures the Streamable HTTP transport provider with an McpTransportContextExtractor
     * that extracts the authenticated AgentIdentity from Spring Security into McpTransportContext.
     */
    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper,
            McpServerStreamableHttpProperties properties) {

        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(serverRequest -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth == null && serverRequest != null && serverRequest.servletRequest() != null) {
                        Principal principal = serverRequest.servletRequest().getUserPrincipal();
                        if (principal instanceof Authentication a) {
                            auth = a;
                        }
                    }

                    if (auth instanceof AgentAuthenticationToken token) {
                        AgentIdentity identity = token.getAgentIdentity();
                        log.debug("Extracted AgentIdentity for MCP transport context: clientId={}", identity.clientId());
                        return McpTransportContext.create(Map.of(
                                "agentIdentity", identity,
                                "agentId", identity.clientId()
                        ));
                    }

                    log.warn("No AgentAuthenticationToken found in SecurityContext for MCP request; failing closed with empty context");
                    return McpTransportContext.EMPTY;
                })
                .build();
    }

    /**
     * Registers a native MCP sync tool-list filter that governs discovery per authenticated agent.
     * Annotated with @Primary to override the default servletMcpSyncServerCustomizer while preserving immediateExecution.
     */
    @Bean
    @Primary
    public McpSyncServerCustomizer mcpToolListFilterCustomizer(RegisteredToolRepository toolRepository) {
        return spec -> {
            spec.immediateExecution(true);
            spec.addToolFilter((context, tool) -> {
                if (context == null) {
                    log.debug("Tool filter denied '{}': missing McpTransportContext", tool.name());
                    return false;
                }

                Object identityObj = context.get("agentIdentity");
                if (!(identityObj instanceof AgentIdentity agent)) {
                    log.debug("Tool filter denied '{}': no valid AgentIdentity in context", tool.name());
                    return false;
                }

                String toolName = tool.name();

                // Built-in approval result retrieval tool (FR4)
                if ("get_approval_result".equals(toolName)) {
                    return true;
                }

                // Baseline M01 fixture tools
                if ("m01_status".equals(toolName)) {
                    return true;
                }
                if ("m01_restricted_admin_tool".equals(toolName)) {
                    boolean isAdmin = agent.hasRole("ADMIN") || agent.hasRole("ROLE_ADMIN") || agent.hasScope("tool:admin");
                    log.debug("Tool filter check for '{}' by agent '{}': isAdmin={}", toolName, agent.clientId(), isAdmin);
                    return isAdmin;
                }

                // Registered dynamic tools: check permissions in registered tool repository
                boolean permitted = toolRepository.isAgentPermitted(toolName, agent.clientId());
                log.debug("Tool filter check for '{}' by agent '{}': permitted={}", toolName, agent.clientId(), permitted);
                return permitted;
            });
        };
    }
}



