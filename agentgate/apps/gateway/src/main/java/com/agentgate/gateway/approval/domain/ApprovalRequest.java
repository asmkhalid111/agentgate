package com.agentgate.gateway.approval.domain;

import java.time.Instant;

/**
 * Persistent human approval request entity for high-risk tool calls (FR4).
 * <p>
 * Security & Governance Notes:
 * <ul>
 * <li><b>Original Arguments:</b> Preserved immutably as originally passed by
 * the calling agent.</li>
 * <li><b>Approved Arguments:</b> Stored separately when an approver reviews or
 * edits arguments.</li>
 * <li><b>Future Execution Safety:</b> When future approved execution is
 * implemented, edited arguments
 * must be revalidated through the JSON Schema guard and applicable
 * authorization/safety checks
 * before downstream tool dispatch.</li>
 * </ul>
 */
public record ApprovalRequest(
        String id,
        String agentId,
        String userId,
        String toolName,
        String policyRuleId,
        String originalArgumentsJson,
        String approvedArgumentsJson,
        ApprovalStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        ExecutionState executionState,
        Instant executedAt,
        String executionResultJson,
        String executionError) {
    public ApprovalRequest(
            String id,
            String agentId,
            String userId,
            String toolName,
            String policyRuleId,
            String originalArgumentsJson,
            String approvedArgumentsJson,
            ApprovalStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant decidedAt,
            String decidedBy,
            String decisionReason) {
        this(
                id, agentId, userId, toolName, policyRuleId,
                originalArgumentsJson, approvedArgumentsJson, status,
                createdAt, expiresAt, decidedAt, decidedBy, decisionReason,
                ExecutionState.NOT_DISPATCHED, null, null, null);
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    public boolean isExpired(Instant now) {
        return status == ApprovalStatus.PENDING && now.isAfter(expiresAt);
    }
}
