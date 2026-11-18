package com.agentgate.gateway.policy.domain;

import java.util.List;

public record PolicyRule(
        String id,
        String name,
        String agentPattern,
        String toolPattern,
        List<StructuredCondition> conditions,
        PolicyAction action,
        boolean dryRun,
        int priority,
        int version,
        boolean enabled
) {}
