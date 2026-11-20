package com.agentgate.gateway.config;

import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.killswitch.dto.KillSwitchCheckResult;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.mcp.DynamicToolCallbackProvider;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreaker;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreakerRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

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
     * Enforces:
     * 1. Preserved metadata tools (e.g. get_approval_result).
     * 2. Direct agent permissions (unauthorized tools remain hidden).
     * 3. Enabled status (disabled tools remain hidden).
     * 4. FR8 emergency kill switches (agent, tool, global-write).
     * 5. FR10 circuit breaker availability (OPEN tools hidden during cooldown, discoverable for HALF_OPEN trial).
     */
    @Bean
    @Primary
    public McpSyncServerCustomizer mcpToolListFilterCustomizer(
            RegisteredToolRepository toolRepository,
            @Autowired(required = false) KillSwitchService killSwitchService,
            @Autowired(required = false) ToolCircuitBreakerRegistry circuitBreakerRegistry) {
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

                // 1. Built-in approval result retrieval tool (FR4): always preserved
                if ("get_approval_result".equals(toolName)) {
                    return true;
                }

                // 2. Baseline M01 fixture tools
                if ("m01_status".equals(toolName)) {
                    return true;
                }
                if ("m01_restricted_admin_tool".equals(toolName)) {
                    boolean isAdmin = agent.hasRole("ADMIN") || agent.hasRole("ROLE_ADMIN") || agent.hasScope("tool:admin");
                    log.debug("Tool filter check for '{}' by agent '{}': isAdmin={}", toolName, agent.clientId(), isAdmin);
                    return isAdmin;
                }

                // 3. Registered dynamic tools
                // Authorization check FIRST: never leak tool existence or health state to unauthorized agents
                boolean permitted = toolRepository.isAgentPermitted(toolName, agent.clientId());
                if (!permitted) {
                    log.debug("Tool filter denied '{}' for agent '{}': unauthorized", toolName, agent.clientId());
                    return false;
                }

                Optional<RegisteredTool> toolOpt = toolRepository.findByName(toolName);
                if (toolOpt.isEmpty()) {
                    return false;
                }
                RegisteredTool registeredTool = toolOpt.get();

                // Enabled check: disabled tools remain hidden
                if (!registeredTool.enabled()) {
                    log.debug("Tool filter denied '{}' for agent '{}': disabled", toolName, agent.clientId());
                    return false;
                }

                // FR8 Emergency Kill-Switch check (agent, tool, global-write)
                if (killSwitchService != null) {
                    KillSwitchCheckResult killCheck = killSwitchService.checkBlocked(
                            agent.clientId(),
                            toolName,
                            registeredTool.isWriteOperation()
                    );
                    if (killCheck.blocked()) {
                        log.debug("Tool filter denied '{}' for agent '{}': emergency kill switch active ({})",
                                toolName, agent.clientId(), killCheck.message());
                        return false;
                    }
                }

                // FR10 Circuit Breaker availability check
                if (circuitBreakerRegistry != null) {
                    ToolCircuitBreaker breaker = circuitBreakerRegistry.getBreaker(toolName);
                    if (!breaker.isAvailableForDiscovery()) {
                        log.debug("Tool filter denied '{}' for agent '{}': circuit OPEN during cooldown", toolName, agent.clientId());
                        return false;
                    }
                }

                log.debug("Tool filter allowed '{}' for agent '{}'", toolName, agent.clientId());
                return true;
            });
        };
    }
}
