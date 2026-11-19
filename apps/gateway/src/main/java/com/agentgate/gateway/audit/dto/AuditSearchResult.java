package com.agentgate.gateway.audit.dto;

import com.agentgate.gateway.audit.domain.AuditEvent;

import java.util.List;

/**
 * Paginated result container for audit search queries (FR7).
 */
public record AuditSearchResult(
        List<AuditEvent> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AuditSearchResult of(List<AuditEvent> content, int page, int size, long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new AuditSearchResult(
                content != null ? content : List.of(),
                page,
                size,
                totalElements,
                totalPages
        );
    }
}
