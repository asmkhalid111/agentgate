package com.agentgate.gateway.policy.domain;

/**
 * Result of policy rule evaluation for a tool execution request.
 */
public record PolicyDecision(
        PolicyAction action,
        String matchedRuleId,
        String matchedRuleName,
        String reason,
        boolean dryRun,
        long evaluationTimeMs
) {
    public static PolicyDecision allow(String ruleId, String ruleName, String reason, boolean dryRun, long evalMs) {
        return new PolicyDecision(PolicyAction.ALLOW, ruleId, ruleName, reason, dryRun, evalMs);
    }

    public static PolicyDecision deny(String ruleId, String ruleName, String reason, boolean dryRun, long evalMs) {
        return new PolicyDecision(PolicyAction.DENY, ruleId, ruleName, reason, dryRun, evalMs);
    }

    public static PolicyDecision requireApproval(String ruleId, String ruleName, String reason, boolean dryRun, long evalMs) {
        return new PolicyDecision(PolicyAction.REQUIRE_APPROVAL, ruleId, ruleName, reason, dryRun, evalMs);
    }

    public static PolicyDecision defaultDeny(String reason, long evalMs) {
        return new PolicyDecision(PolicyAction.DENY, "DEFAULT_DENY", "Default Deny Rule", reason, false, evalMs);
    }
}
