package com.agentgate.gateway.approval.webhook;

import java.time.Instant;

/**
 * Minimal, privacy-safe webhook notification payload (FR4 / Reviewer Correction C).
 * Strictly contains only operational metadata necessary for approver alerting.
 * Excludes userId, original/edited arguments, credentials, and sensitive input/output data.
 */
public record ApprovalWebhookPayload(
        String approvalId,
        String agentId,
        String toolName,
        String status,
        Instant createdAt,
        Instant expiresAt,
        String policyRuleId
) {
}
