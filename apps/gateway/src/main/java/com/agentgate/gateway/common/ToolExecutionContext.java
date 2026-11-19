package com.agentgate.gateway.common;

import com.agentgate.gateway.registry.domain.RiskLevel;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Common context passed along the gateway execution pipeline, guards, and policy engine.
 */
public record ToolExecutionContext(
        String agentId,
        String toolName,
        Map<String, Object> arguments,
        String userId,
        RiskLevel riskLevel,
        Set<String> agentRoles
) {
    public ToolExecutionContext(
            String agentId,
            String toolName,
            Map<String, Object> arguments,
            String userId,
            RiskLevel riskLevel
    ) {
        this(agentId, toolName, arguments, userId, riskLevel, Collections.emptySet());
    }

    public ToolExecutionContext {
        agentRoles = agentRoles != null ? Collections.unmodifiableSet(agentRoles) : Collections.emptySet();
    }

    public ToolExecutionContext withRiskLevel(RiskLevel newRisk) {
        return new ToolExecutionContext(agentId, toolName, arguments, userId, newRisk, agentRoles);
    }

    public ToolExecutionContext withRoles(Set<String> newRoles) {
        return new ToolExecutionContext(agentId, toolName, arguments, userId, riskLevel, newRoles);
    }

    /**
     * Clearance check: only explicit HIGH_CLEARANCE authority may bypass PII redaction.
     * Administrative roles (ADMIN, ROLE_ADMIN) do NOT grant sensitive data clearance.
     */
    public boolean hasHighClearance() {
        return agentRoles.contains("HIGH_CLEARANCE") || agentRoles.contains("ROLE_HIGH_CLEARANCE");
    }
}
