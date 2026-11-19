package com.agentgate.gateway.audit.dto;

import com.agentgate.gateway.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.List;

/**
 * Structured container for JSON audit trail exports (FR7).
 */
public record AuditExportResponse(
        Instant exportTimestamp,
        int recordCount,
        List<AuditEvent> records
) {
    public static AuditExportResponse of(List<AuditEvent> records) {
        return new AuditExportResponse(
                Instant.now(),
                records != null ? records.size() : 0,
                records != null ? records : List.of()
        );
    }
}
