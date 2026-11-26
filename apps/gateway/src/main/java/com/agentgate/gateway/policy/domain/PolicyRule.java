package com.agentgate.gateway.policy.domain;

import java.util.List;

public record PolicyRule(
        String id,
        @jakarta.validation.constraints.NotBlank String name,
        @jakarta.validation.constraints.NotBlank String agentPattern,
        @jakarta.validation.constraints.NotBlank String toolPattern,
        List<StructuredCondition> conditions,
        PolicyAction action,
        boolean dryRun,
        int priority,
        int version,
        boolean enabled
) {}
