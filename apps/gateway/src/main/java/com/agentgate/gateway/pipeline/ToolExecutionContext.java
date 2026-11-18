package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.domain.RiskLevel;

import java.util.Map;

/**
 * Context passed along the gateway execution pipeline.
 */
public record ToolExecutionContext(
        String agentId,
        String toolName,
        Map<String, Object> arguments,
        String userId,
        RiskLevel riskLevel
) {
    public ToolExecutionContext withRiskLevel(RiskLevel newRisk) {
        return new ToolExecutionContext(agentId, toolName, arguments, userId, newRisk);
    }
}
