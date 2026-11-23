package com.agentgate.gateway.pipeline.benchmark;

/**
 * Thread-local context for high-resolution nanosecond benchmark timing and profiling.
 * Captures gateway total duration, downstream tool duration, and granular component breakdown.
 */
public final class BenchmarkTimingContext {

    private static volatile boolean timingEnabled = false;

    private static final ThreadLocal<Long> GATEWAY_START_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> DOWNSTREAM_DURATION_NS = new ThreadLocal<>();
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    // Profiling breakdown spans
    private static final ThreadLocal<Long> SECURITY_INGRESS_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> PERMISSION_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> RATE_LIMIT_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> SCHEMA_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> POLICY_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> CIRCUIT_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> AUDIT_TOTAL_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> AUDIT_LOCK_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> AUDIT_HASH_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> AUDIT_DB_WRITE_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> AUDIT_COMMIT_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> PII_NS = new ThreadLocal<>();
    private static final ThreadLocal<Long> SERIALIZATION_NS = new ThreadLocal<>();

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

    public static void setSecurityIngressNs(long ns) { SECURITY_INGRESS_NS.set(ns); }
    public static Long getSecurityIngressNs() { return SECURITY_INGRESS_NS.get(); }

    public static void setPermissionNs(long ns) { PERMISSION_NS.set(ns); }
    public static Long getPermissionNs() { return PERMISSION_NS.get(); }

    public static void setRateLimitNs(long ns) { RATE_LIMIT_NS.set(ns); }
    public static Long getRateLimitNs() { return RATE_LIMIT_NS.get(); }

    public static void setSchemaNs(long ns) { SCHEMA_NS.set(ns); }
    public static Long getSchemaNs() { return SCHEMA_NS.get(); }

    public static void setPolicyNs(long ns) { POLICY_NS.set(ns); }
    public static Long getPolicyNs() { return POLICY_NS.get(); }

    public static void setCircuitNs(long ns) { CIRCUIT_NS.set(ns); }
    public static Long getCircuitNs() { return CIRCUIT_NS.get(); }

    public static void setAuditTotalNs(long ns) { AUDIT_TOTAL_NS.set(ns); }
    public static Long getAuditTotalNs() { return AUDIT_TOTAL_NS.get(); }

    public static void setAuditLockNs(long ns) { AUDIT_LOCK_NS.set(ns); }
    public static Long getAuditLockNs() { return AUDIT_LOCK_NS.get(); }

    public static void setAuditHashNs(long ns) { AUDIT_HASH_NS.set(ns); }
    public static Long getAuditHashNs() { return AUDIT_HASH_NS.get(); }

    public static void setAuditDbWriteNs(long ns) { AUDIT_DB_WRITE_NS.set(ns); }
    public static Long getAuditDbWriteNs() { return AUDIT_DB_WRITE_NS.get(); }

    public static void setAuditCommitNs(long ns) { AUDIT_COMMIT_NS.set(ns); }
    public static Long getAuditCommitNs() { return AUDIT_COMMIT_NS.get(); }

    public static void setPiiNs(long ns) { PII_NS.set(ns); }
    public static Long getPiiNs() { return PII_NS.get(); }

    public static void setSerializationNs(long ns) { SERIALIZATION_NS.set(ns); }
    public static Long getSerializationNs() { return SERIALIZATION_NS.get(); }

    public static void clear() {
        GATEWAY_START_NS.remove();
        DOWNSTREAM_DURATION_NS.remove();
        CORRELATION_ID.remove();
        SECURITY_INGRESS_NS.remove();
        PERMISSION_NS.remove();
        RATE_LIMIT_NS.remove();
        SCHEMA_NS.remove();
        POLICY_NS.remove();
        CIRCUIT_NS.remove();
        AUDIT_TOTAL_NS.remove();
        AUDIT_LOCK_NS.remove();
        AUDIT_HASH_NS.remove();
        AUDIT_DB_WRITE_NS.remove();
        AUDIT_COMMIT_NS.remove();
        PII_NS.remove();
        SERIALIZATION_NS.remove();
    }
}
