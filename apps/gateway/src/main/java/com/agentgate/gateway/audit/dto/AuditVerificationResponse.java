package com.agentgate.gateway.audit.dto;

import com.agentgate.gateway.audit.domain.AuditVerificationResult;

/**
 * Administrative response DTO reporting SHA-256 hash chain cryptographic integrity status (FR7).
 */
public record AuditVerificationResponse(
        boolean valid,
        long recordsChecked,
        Long lastSequence,
        Long corruptedSequence,
        String failureReason
) {
    public static AuditVerificationResponse from(AuditVerificationResult result, Long lastSequence) {
        return new AuditVerificationResponse(
                result.valid(),
                result.recordsChecked(),
                lastSequence,
                result.corruptedSequence(),
                result.failureReason()
        );
    }
}
