package com.agentgate.gateway.approval.dto;

import java.time.Instant;

/**
 * Lightweight summary of an approval request for list endpoints (Reviewer Correction C).
 * Omits detailed argument payloads to minimize exposed sensitive data.
 */
public record ApprovalSummaryResponse(
        String id,
        String agentId,
        String userId,
        String toolName,
        String policyRuleId,
        String status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason
) {}
