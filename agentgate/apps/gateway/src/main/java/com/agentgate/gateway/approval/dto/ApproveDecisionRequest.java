package com.agentgate.gateway.approval.dto;

import java.util.Map;

/**
 * Request payload for approving a pending request.
 * editedArguments is optional; if omitted or empty, original arguments are approved as-is.
 */
public record ApproveDecisionRequest(
        Map<String, Object> editedArguments
) {}
