package com.agentgate.gateway.approval.dto;

import java.time.Instant;

/**
 * Structured response for agent approval result retrieval (FR4).
 * Decouples human approval decision status from downstream tool execution state.
 */
public record ApprovalResultResponse(
        String approvalId,
        String toolName,
        String status,
        String executionState,
        Object result,
        String error,
        String message,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        Instant executedAt
) {
}
