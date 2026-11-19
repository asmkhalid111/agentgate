package com.agentgate.gateway.registry.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Domain entity representing a registered downstream tool (REST or MCP).
 */
public record RegisteredTool(
        String id,
        String name,
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        String owningTeam,
        String endpointUrl,
        ProtocolType protocolType,
        String httpMethod,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    private static final Set<String> WRITE_HTTP_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> READ_HTTP_METHODS = Set.of("GET", "HEAD");

    /**
     * Determines whether this tool performs a mutating / write operation (FR6).
     * <ul>
     *   <li><b>REST tools:</b> POST, PUT, PATCH, DELETE are write operations; GET and HEAD are read-only.</li>
     *   <li><b>MCP tools:</b> Evaluates explicit read metadata (e.g. GET/HEAD) if configured.
     *       If no reliable read classification exists, conservatively defaults to write-capable.</li>
     * </ul>
     */
    public boolean isWriteOperation() {
        if (protocolType == ProtocolType.REST) {
            if (httpMethod != null) {
                String m = httpMethod.trim().toUpperCase();
                if (READ_HTTP_METHODS.contains(m)) {
                    return false;
                }
                if (WRITE_HTTP_METHODS.contains(m)) {
                    return true;
                }
            }
            return true; // Conservative fallback for REST
        }
        if (protocolType == ProtocolType.MCP) {
            // For MCP tools: check if explicitly classified as read-only via trusted metadata
            if (httpMethod != null) {
                String m = httpMethod.trim().toUpperCase();
                if (READ_HTTP_METHODS.contains(m)) {
                    return false;
                }
            }
            // Conservative default: treat unclassified MCP tools as write-capable
            return true;
        }
        return true;
    }

    public RegisteredTool withRiskLevel(RiskLevel newRisk) {
        return new RegisteredTool(id, name, description, inputSchema, newRisk, owningTeam, endpointUrl, protocolType, httpMethod, enabled, createdAt, Instant.now());
    }

    public RegisteredTool withEnabled(boolean newEnabled) {
        return new RegisteredTool(id, name, description, inputSchema, riskLevel, owningTeam, endpointUrl, protocolType, httpMethod, newEnabled, createdAt, Instant.now());
    }
}

