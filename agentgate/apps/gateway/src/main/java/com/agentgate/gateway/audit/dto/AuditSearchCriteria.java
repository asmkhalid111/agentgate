package com.agentgate.gateway.audit.dto;

import java.time.Instant;

/**
 * Filter criteria and bounded pagination parameters for administrative audit log queries (FR7).
 */
public record AuditSearchCriteria(
        String agentId,
        String toolName,
        String approvalId,
        String outcome,
        String decision,
        Instant from,
        Instant to,
        int page,
        int size
) {
    public AuditSearchCriteria {
        if (page < 0) page = 0;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
        if (agentId != null && agentId.isBlank()) agentId = null;
        if (toolName != null && toolName.isBlank()) toolName = null;
        if (approvalId != null && approvalId.isBlank()) approvalId = null;
        if (outcome != null && outcome.isBlank()) outcome = null;
        if (decision != null && decision.isBlank()) decision = null;
    }
}
