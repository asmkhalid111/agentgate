package com.agentgate.gateway.audit.controller;

import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditVerificationResult;
import com.agentgate.gateway.audit.dto.AuditExportResponse;
import com.agentgate.gateway.audit.dto.AuditSearchCriteria;
import com.agentgate.gateway.audit.dto.AuditSearchResult;
import com.agentgate.gateway.audit.dto.AuditVerificationResponse;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.audit.service.AuditVerificationService;
import com.agentgate.gateway.audit.util.CsvExportUtil;
import com.agentgate.gateway.identity.HumanAdminValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Protected REST controller for administrative audit log search, export, and SHA-256 integrity verification (FR7).
 * <p>
 * Strictly restricted to verified human administrators with ROLE_ADMIN. Automated agent tokens and
 * approver-only users are denied access.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditAdminController {

    private static final Logger log = LoggerFactory.getLogger(AuditAdminController.class);

    private final AuditRepository auditRepository;
    private final AuditVerificationService auditVerificationService;
    private final HumanAdminValidator humanAdminValidator;

    public AuditAdminController(
            AuditRepository auditRepository,
            AuditVerificationService auditVerificationService,
            HumanAdminValidator humanAdminValidator) {
        this.auditRepository = auditRepository;
        this.auditVerificationService = auditVerificationService;
        this.humanAdminValidator = humanAdminValidator;
    }

    /**
     * Bounded, paginated search and filtering of audit events.
     */
    @GetMapping
    public ResponseEntity<AuditSearchResult> search(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String approvalId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        String adminUser = humanAdminValidator.verifyHumanAdmin(authentication);
        log.debug("Admin '{}' searching audit records: agent={}, tool={}, approvalId={}, outcome={}, decision={}, from={}, to={}, page={}, size={}",
                adminUser, agentId, toolName, approvalId, outcome, decision, from, to, page, size);

        AuditSearchCriteria criteria = new AuditSearchCriteria(agentId, toolName, approvalId, outcome, decision, from, to, page, size);
        AuditSearchResult result = auditRepository.search(criteria);
        return ResponseEntity.ok(result);
    }

    /**
     * Exports audit records in JSON or CSV format up to a maximum of 1,000 records.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String approvalId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(defaultValue = "json") String format,
            Authentication authentication) {

        String adminUser = humanAdminValidator.verifyHumanAdmin(authentication);
        int boundedLimit = Math.min(Math.max(1, limit), 1000);
        log.info("Admin '{}' requested audit export [format={}, limit={}]", adminUser, format, boundedLimit);

        AuditSearchCriteria criteria = new AuditSearchCriteria(agentId, toolName, approvalId, outcome, decision, from, to, 0, boundedLimit);
        List<AuditEvent> events = auditRepository.export(criteria, boundedLimit);

        if ("csv".equalsIgnoreCase(format)) {
            String csvContent = CsvExportUtil.toCsv(events);
            String filename = "audit-export-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-") + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(csvContent);
        }

        return ResponseEntity.ok(AuditExportResponse.of(events));
    }

    /**
     * Cryptographically verifies the SHA-256 hash chain and chain-state concordance.
     */
    @PostMapping("/verify")
    public ResponseEntity<AuditVerificationResponse> verify(Authentication authentication) {
        String adminUser = humanAdminValidator.verifyHumanAdmin(authentication);
        log.info("Admin '{}' initiated SHA-256 audit chain verification", adminUser);

        AuditVerificationResult result = auditVerificationService.verifyIntegrity();
        Long lastSequence = auditRepository.getChainState().lastSequence();
        return ResponseEntity.ok(AuditVerificationResponse.from(result, lastSequence));
    }
}
