package com.agentgate.gateway.pipeline.benchmark;

/**
 * Thread-local context for high-resolution nanosecond benchmark timing.
 * Captures gateway total duration and downstream tool adapter duration.
 */
public final class BenchmarkTimingContext {

    private static volatile boolean timingEnabled = false;

    private static final ThreadLocal<Long> GATEWAY_START_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> DOWNSTREAM_DURATION_NS = new ThreadLocal<>();
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private BenchmarkTimingContext() {}

    public static void setTimingEnabled(boolean enabled) {
        timingEnabled = enabled;
    }

    public static boolean isTimingEnabled() {
        return timingEnabled;
    }

    public static void setGatewayStartNs(long startNs) {
        GATEWAY_START_NS.set(startNs);
    }

    public static Long getGatewayStartNs() {
        return GATEWAY_START_NS.get();
    }

    public static void setDownstreamDurationNs(long durationNs) {
        DOWNSTREAM_DURATION_NS.set(durationNs);
    }

    public static Long getDownstreamDurationNs() {
        return DOWNSTREAM_DURATION_NS.get();
    }

    public static void setCorrelationId(String id) {
        CORRELATION_ID.set(id);
    }

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        GATEWAY_START_NS.remove();
        DOWNSTREAM_DURATION_NS.remove();
        CORRELATION_ID.remove();
    }
}
