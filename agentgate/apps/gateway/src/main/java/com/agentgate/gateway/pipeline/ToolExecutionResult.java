package com.agentgate.gateway.pipeline;

/**
 * Result returned by downstream tool invocation through the pipeline.
 */
public record ToolExecutionResult(
        boolean success,
        Object data,
        String error,
        long latencyMs,
        Throwable cause,
        Integer httpStatusCode
) {
    public ToolExecutionResult(boolean success, Object data, String error, long latencyMs) {
        this(success, data, error, latencyMs, null, null);
    }

    public static ToolExecutionResult success(Object data, long latencyMs) {
        return new ToolExecutionResult(true, data, null, latencyMs, null, null);
    }

    public static ToolExecutionResult failure(String error, long latencyMs) {
        return new ToolExecutionResult(false, null, error, latencyMs, null, null);
    }

    public static ToolExecutionResult failure(String error, long latencyMs, Throwable cause, Integer httpStatusCode) {
        return new ToolExecutionResult(false, null, error, latencyMs, cause, httpStatusCode);
    }

    public static ToolExecutionResult failure(String error, long latencyMs, Throwable cause) {
        return new ToolExecutionResult(false, null, error, latencyMs, cause, null);
    }
}
