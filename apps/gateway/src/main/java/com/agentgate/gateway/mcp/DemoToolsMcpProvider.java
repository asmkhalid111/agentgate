package com.agentgate.gateway.mcp;

import com.agentgate.gateway.identity.AgentContextHolder;
import com.agentgate.gateway.identity.AgentIdentity;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal static MCP test-fixture tools for M01 connectivity and authorization verification.
 */
@Component
public class DemoToolsMcpProvider {

    /**
     * Permitted tool: accessible to all authenticated agents with agent:invoke authority.
     */
    @Tool(description = "Returns the status of the AgentGate governance gateway and echoes the input message")
    public Map<String, Object> m01_status(
            @ToolParam(description = "Optional echo message", required = false) String message) {
        String callingAgent = AgentContextHolder.getCurrentAgent()
                .map(AgentIdentity::clientId)
                .orElse("anonymous");

        return Map.of(
                "status", "HEALTHY",
                "milestone", "M01",
                "callingAgent", callingAgent,
                "echo", message != null ? message : "",
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Restricted tool: strictly requires ROLE_ADMIN or tool:admin scope.
     * Unauthorized agents calling this tool will be rejected with an AccessDeniedException.
     */
    @Tool(description = "Restricted administrative maintenance tool requiring elevated admin authority")
    public Map<String, Object> m01_restricted_admin_tool(
            @ToolParam(description = "Optional action description", required = false) String action) {
        AgentIdentity agent = AgentContextHolder.getCurrentAgent()
                .orElseThrow(() -> new AccessDeniedException("Unauthorized: No authenticated agent identity found"));

        if (!agent.hasRole("ADMIN") && !agent.hasRole("ROLE_ADMIN") && !agent.hasScope("tool:admin")) {
            throw new AccessDeniedException("Access Denied: Agent '" + agent.clientId() 
                    + "' lacks required admin authority for m01_restricted_admin_tool");
        }

        return Map.of(
                "status", "EXECUTED",
                "authorizedAgent", agent.clientId(),
                "action", action != null ? action : "default_action",
                "result", "Administrative task completed successfully"
        );
    }
}
