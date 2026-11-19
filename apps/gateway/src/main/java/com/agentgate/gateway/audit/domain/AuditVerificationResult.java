package com.agentgate.gateway.audit.domain;

/**
 * Result of cryptographically verifying the audit hash chain (FR7).
 */
public record AuditVerificationResult(
        boolean valid,
        long recordsChecked,
        Long corruptedSequence,
        String failureReason
) {
    public static AuditVerificationResult success(long count) {
        return new AuditVerificationResult(true, count, null, null);
    }

    public static AuditVerificationResult corrupted(long seq, String reason, long count) {
        return new AuditVerificationResult(false, count, seq, reason);
    }
}
