package com.agentgate.gateway.audit.service;

import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditRecordDraft;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Core service for recording immutable, tamper-evident audit events into the SHA-256 hash chain (FR7).
 * <p>
 * Supports two explicit recording modes:
 * <ul>
 *   <li><b>Mode A (Atomic business-transition audit):</b> Appends audit events in the same PostgreSQL
 *       transaction as the corresponding state change (REQUIRED). If the business transaction rolls back,
 *       the audit event also rolls back. If audit persistence fails, the business transaction rolls back.</li>
 *   <li><b>Mode B (Independently committed dispatch audit):</b> Pre-dispatch write intents and post-dispatch
 *       outcomes commit independently (REQUIRES_NEW) so evidence is persisted before external adapter calls
 *       without holding chain locks during network execution.</li>
 * </ul>
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Set<String> SECRET_FIELD_PATTERNS = Set.of(
            "password", "token", "accesstoken", "authorization", "apikey", "clientsecret", "secret"
    );

    private final AuditRepository auditRepository;
    private final AuditHashEngine auditHashEngine;
    private final PiiRedactionGuard piiRedactionGuard;
    private final ObjectMapper objectMapper;

    public AuditRecorder(
            AuditRepository auditRepository,
            AuditHashEngine auditHashEngine,
            PiiRedactionGuard piiRedactionGuard,
            ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.auditHashEngine = auditHashEngine;
        this.piiRedactionGuard = piiRedactionGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Mode A: Appends an audit event participating in the caller's active database transaction (REQUIRED).
     * If the caller's transaction rolls back, this audit record rolls back with it.
     * If audit persistence fails, the outer business transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent append(AuditRecordDraft draft) {
        return doAppend(draft);
    }

    /**
     * Mode B: Appends an audit event under a fresh independent committed transaction (REQUIRES_NEW).
     * Locks the chain state tip (id = 1), increments sequence, inserts into audit_log, updates the tip,
     * and commits immediately, releasing the lock before downstream network operations.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent appendIndependent(AuditRecordDraft draft) {
        return doAppend(draft);
    }

    private AuditEvent doAppend(AuditRecordDraft draft) {
        AuditChainState tip = auditRepository.getChainStateForUpdate();
        long nextSequence = tip.lastSequence() + 1;
        String prevHash = tip.lastHash();

        Instant timestamp = (draft.eventTimestamp() != null
                ? draft.eventTimestamp()
                : Instant.now()).truncatedTo(ChronoUnit.MILLIS);

        AuditRecordDraft normalizedDraft = new AuditRecordDraft(
                timestamp,
                draft.eventType(),
                draft.agentId(),
                draft.userId(),
                draft.actorId(),
                draft.toolName(),
                draft.redactedArgumentsJson() != null ? draft.redactedArgumentsJson() : "{}",
                draft.policyRuleId(),
                draft.policyRuleName(),
                draft.decision(),
                draft.approvalId(),
                draft.outcome(),
                draft.latencyMs(),
                draft.errorMessage()
        );

        String recordHash = auditHashEngine.computeRecordHash(nextSequence, prevHash, normalizedDraft);

        AuditEvent event = new AuditEvent(
                nextSequence,
                timestamp,
                normalizedDraft.eventType(),
                normalizedDraft.agentId(),
                normalizedDraft.userId(),
                normalizedDraft.actorId(),
                normalizedDraft.toolName(),
                normalizedDraft.redactedArgumentsJson(),
                normalizedDraft.policyRuleId(),
                normalizedDraft.policyRuleName(),
                normalizedDraft.decision(),
                normalizedDraft.approvalId(),
                normalizedDraft.outcome(),
                normalizedDraft.latencyMs(),
                normalizedDraft.errorMessage(),
                prevHash,
                recordHash
        );

        auditRepository.insertAuditEvent(event);
        auditRepository.updateChainState(nextSequence, recordHash);

        log.debug("Audited event: seq={}, type={}, tool={}, outcome={}, hash={}",
                nextSequence, event.eventType(), event.toolName(), event.outcome(), recordHash);

        return event;
    }

    /**
     * Records a direct tool execution post-dispatch outcome (Mode B: REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordDirectToolExecution(
            String agentId,
            String userId,
            String toolName,
            Map<String, Object> arguments,
            String ruleId,
            String ruleName,
            String decision,
            long latencyMs,
            String outcome,
            String errorMessage) {

        String scrubbedArgs = scrubArguments(arguments);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "DIRECT_EXECUTION",
                agentId,
                userId,
                agentId,
                toolName,
                scrubbedArgs,
                ruleId,
                ruleName,
                decision != null ? decision : "ALLOW",
                null,
                outcome,
                latencyMs,
                errorMessage
        );
        return doAppend(draft);
    }

    /**
     * Mandatory pre-dispatch intent record for mutating write tools (Mode B: REQUIRES_NEW).
     * Must be committed before downstream tool invocation, releasing the chain lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordWriteDispatchIntent(
            String agentId,
            String userId,
            String toolName,
            Map<String, Object> arguments,
            String ruleId,
            String ruleName,
            String approvalId) {

        String scrubbedArgs = scrubArguments(arguments);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "WRITE_DISPATCH_INTENT",
                agentId,
                userId,
                agentId,
                toolName,
                scrubbedArgs,
                ruleId,
                ruleName,
                "DISPATCH_INTENT",
                approvalId,
                "PENDING_DISPATCH",
                0L,
                null
        );
        return doAppend(draft);
    }

    /**
     * Records a governance denial (policy block, emergency kill switch, rate limit, or schema failure) (Mode B: REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordGovernanceDenial(
            String agentId,
            String userId,
            String toolName,
            Map<String, Object> arguments,
            String decision,
            String ruleId,
            String ruleName,
            String errorMessage,
            long latencyMs) {

        String scrubbedArgs = scrubArguments(arguments);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "GOVERNANCE_DENIAL",
                agentId,
                userId,
                agentId,
                toolName != null ? toolName : "UNKNOWN",
                scrubbedArgs,
                ruleId,
                ruleName,
                decision,
                null,
                "BLOCKED",
                latencyMs,
                errorMessage
        );
        return doAppend(draft);
    }

    /**
     * Records an approval request lifecycle creation event (Mode A: REQUIRED).
     * Participates in the caller's database transaction so creation and audit commit or rollback atomically.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordApprovalRequested(
            String approvalId,
            String agentId,
            String userId,
            String toolName,
            String originalArgumentsJson,
            String policyRuleId,
            String policyRuleName) {

        String scrubbedArgs = scrubArgumentsJson(originalArgumentsJson);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "APPROVAL_REQUESTED",
                agentId,
                userId,
                agentId,
                toolName,
                scrubbedArgs,
                policyRuleId,
                policyRuleName,
                "REQUIRE_APPROVAL",
                approvalId,
                "PENDING_APPROVAL",
                0L,
                null
        );
        return doAppend(draft);
    }

    /**
     * Records a human approval decision or bulk cancellation/expiration event (Mode A: REQUIRED).
     * Participates in the caller's database transaction so decision update and audit commit or rollback atomically.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordApprovalDecision(
            String approvalId,
            String agentId,
            String userId,
            String toolName,
            String policyRuleId,
            String decision,
            String decidedBy,
            String reason,
            String effectiveArgumentsJson) {

        String scrubbedArgs = scrubArgumentsJson(effectiveArgumentsJson);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "APPROVAL_DECISION",
                agentId,
                userId,
                decidedBy != null ? decidedBy : "SYSTEM",
                toolName,
                scrubbedArgs,
                policyRuleId,
                null,
                decision,
                approvalId,
                decision,
                0L,
                reason
        );
        return doAppend(draft);
    }

    /**
     * Records the final execution outcome of a human-approved tool invocation (Mode B: REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordApprovedExecution(
            String approvalId,
            String agentId,
            String userId,
            String toolName,
            String effectiveArgumentsJson,
            String policyRuleId,
            String decidedBy,
            String outcome,
            long latencyMs,
            String errorMessage) {

        String scrubbedArgs = scrubArgumentsJson(effectiveArgumentsJson);
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "APPROVED_EXECUTION",
                agentId,
                userId,
                decidedBy != null ? decidedBy : "SYSTEM",
                toolName,
                scrubbedArgs,
                policyRuleId,
                null,
                "APPROVED_EXECUTION",
                approvalId,
                outcome,
                latencyMs,
                errorMessage
        );
        return doAppend(draft);
    }

    /**
     * Records invocation of built-in MCP governance tools (Mode B: REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordBuiltinToolExecution(
            String agentId,
            String userId,
            String toolName,
            String approvalId,
            long latencyMs,
            String outcome,
            String errorMessage) {

        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(),
                "BUILTIN_TOOL_EXECUTION",
                agentId,
                userId,
                agentId,
                toolName,
                "{}",
                null,
                null,
                "ALLOW",
                approvalId,
                outcome,
                latencyMs,
                errorMessage
        );
        return doAppend(draft);
    }

    /**
     * Scrubs secret credentials and sensitive PII from a map of tool arguments.
     */
    public String scrubArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        Map<String, Object> scrubbed = scrubMapRecursively(arguments);
        return auditHashEngine.serializeCanonicalArguments(scrubbed);
    }

    /**
     * Scrubs secret credentials and sensitive PII from a raw JSON string of tool arguments.
     */
    public String scrubArgumentsJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "{}".equals(rawJson.trim())) {
            return "{}";
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawJson, MAP_TYPE);
            return scrubArguments(parsed);
        } catch (Exception e) {
            String piiScrubbed = piiRedactionGuard.redactString(rawJson);
            return auditHashEngine.serializeCanonicalArguments(Map.of("raw", piiScrubbed));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scrubMapRecursively(Map<String, Object> input) {
        if (input == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSecretField(key)) {
                result.put(key, "[REDACTED_SECRET]");
            } else if (value instanceof String strVal) {
                result.put(key, piiRedactionGuard.redactString(strVal));
            } else if (value instanceof Map<?, ?> subMap) {
                result.put(key, scrubMapRecursively((Map<String, Object>) subMap));
            } else if (value instanceof List<?> subList) {
                result.put(key, scrubListRecursively(subList));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<?> scrubListRecursively(List<?> list) {
        if (list == null) return null;
        return list.stream().map(item -> {
            if (item instanceof String strVal) {
                return piiRedactionGuard.redactString(strVal);
            } else if (item instanceof Map<?, ?> subMap) {
                return scrubMapRecursively((Map<String, Object>) subMap);
            } else if (item instanceof List<?> subList) {
                return scrubListRecursively(subList);
            } else {
                return item;
            }
        }).toList();
    }

    private boolean isSecretField(String fieldName) {
        if (fieldName == null) return false;
        String normalized = fieldName.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String pattern : SECRET_FIELD_PATTERNS) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
