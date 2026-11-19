package com.agentgate.gateway.resilience.circuitbreaker;

/**
 * Exception thrown when execution is fast-rejected by an OPEN or busy HALF_OPEN circuit breaker (FR10).
 */
public class ToolCircuitBreakerException extends RuntimeException {

    private final String toolName;

    public ToolCircuitBreakerException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
