package com.agentgate.gateway.registry.domain;

import java.time.Instant;

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
    public RegisteredTool withRiskLevel(RiskLevel newRisk) {
        return new RegisteredTool(id, name, description, inputSchema, newRisk, owningTeam, endpointUrl, protocolType, httpMethod, enabled, createdAt, Instant.now());
    }

    public RegisteredTool withEnabled(boolean newEnabled) {
        return new RegisteredTool(id, name, description, inputSchema, riskLevel, owningTeam, endpointUrl, protocolType, httpMethod, newEnabled, createdAt, Instant.now());
    }
}
