package com.agentgate.gateway.policy.domain;

/**
 * Deterministic structured policy condition.
 * Replaces arbitrary SpEL expressions with safe, injection-proof comparison operators.
 */
public record StructuredCondition(
        String field,
        ConditionOperator operator,
        Object value
) {}
