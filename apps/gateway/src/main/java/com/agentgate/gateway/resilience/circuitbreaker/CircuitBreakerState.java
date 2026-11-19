package com.agentgate.gateway.resilience.circuitbreaker;

/**
 * States of the tool circuit breaker (FR10).
 */
public enum CircuitBreakerState {
    /**
     * Normal operating state: tool executions proceed downstream.
     */
    CLOSED,

    /**
     * Tripped state after repeated downstream failures: tool executions are fast-rejected.
     */
    OPEN,

    /**
     * Recovery probe state: allows at most one trial execution to probe downstream health.
     */
    HALF_OPEN
}
