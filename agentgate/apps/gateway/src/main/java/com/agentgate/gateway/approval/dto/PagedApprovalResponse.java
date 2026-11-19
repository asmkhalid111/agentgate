package com.agentgate.gateway.approval.dto;

import java.util.List;

/**
 * Standard paginated response for approval lists (Common Requirement C3).
 */
public record PagedApprovalResponse(
        List<ApprovalSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
