package com.agentgate.gateway.resilience.circuitbreaker;

/**
 * Event published when a tool's circuit breaker state transitions (FR10).
 */
public record CircuitBreakerStateChangedEvent(
        String toolName,
        CircuitBreakerState previousState,
        CircuitBreakerState newState
) {}
