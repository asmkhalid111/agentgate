package com.agentgate.gateway.mcp;

import com.agentgate.gateway.resilience.circuitbreaker.CircuitBreakerStateChangedEvent;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listener that publishes best-effort MCP list-change notifications
 * when downstream tool circuit breaker states change (FR10).
 * <p>
 * Ensures notifications are best-effort, never block execution dispatch,
 * and never corrupt circuit state. If clients disconnect or the transport drops,
 * clients should refresh tools/list.
 */
@Component
public class McpCircuitBreakerNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(McpCircuitBreakerNotificationListener.class);
    private final ObjectProvider<McpSyncServer> mcpSyncServerProvider;

    public McpCircuitBreakerNotificationListener(ObjectProvider<McpSyncServer> mcpSyncServerProvider) {
        this.mcpSyncServerProvider = mcpSyncServerProvider;
    }

    @EventListener
    public void onCircuitBreakerStateChanged(CircuitBreakerStateChangedEvent event) {
        mcpSyncServerProvider.ifAvailable(server -> {
            try {
                server.notifyToolsListChanged();
                log.info("Published MCP notifyToolsListChanged for tool '{}' ({} -> {})",
                        event.toolName(), event.previousState(), event.newState());
            } catch (Exception e) {
                log.warn("Best-effort notifyToolsListChanged failed for tool '{}': {}",
                        event.toolName(), e.getMessage());
            }
        });
    }
}
