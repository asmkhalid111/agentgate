package com.agentgate.gateway.approval.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for rejecting a pending request.
 * reason is mandatory.
 */
public record RejectDecisionRequest(
        @NotBlank(message = "Rejection reason is required")
        String reason
) {}
