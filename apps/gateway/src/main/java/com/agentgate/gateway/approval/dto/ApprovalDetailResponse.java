package com.agentgate.gateway.approval.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Detailed representation of an approval request, including structured original and approved arguments.
 */
public record ApprovalDetailResponse(
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
        String decisionReason,
        Map<String, Object> originalArguments,
        Map<String, Object> approvedArguments
) {}
