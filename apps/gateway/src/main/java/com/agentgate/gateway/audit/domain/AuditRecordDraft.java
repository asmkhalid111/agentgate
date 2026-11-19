package com.agentgate.gateway.audit.domain;

import java.time.Instant;

/**
 * Data required to construct a new audit event before sequence number and SHA-256 hash are assigned.
 */
public record AuditRecordDraft(
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
        String errorMessage
) {
}
