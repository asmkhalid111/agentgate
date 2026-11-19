package com.agentgate.gateway.audit.domain;

import java.time.Instant;

/**
 * Immutable audit log record representing an event in the tamper-evident SHA-256 hash chain (FR7).
 */
public record AuditEvent(
        long sequenceNumber,
        Instant eventTimestamp,
        String eventType,
        String agentId,
        String userId,
        String actorId,
        String toolName,
        String redactedArgumentsJson,
        String policyRuleId,
        String policyRuleName,
        String decision,
        String approvalId,
        String outcome,
        long latencyMs,
        String errorMessage,
        String prevRecordHash,
        String recordHash
) {
}
