package com.agentgate.gateway.pipeline;

/**
 * Result returned by downstream tool invocation through the pipeline.
 */
public record ToolExecutionResult(
        boolean success,
        Object data,
        String error,
        long latencyMs
) {
    public static ToolExecutionResult success(Object data, long latencyMs) {
        return new ToolExecutionResult(true, data, null, latencyMs);
    }

    public static ToolExecutionResult failure(String error, long latencyMs) {
        return new ToolExecutionResult(false, null, error, latencyMs);
    }
}
