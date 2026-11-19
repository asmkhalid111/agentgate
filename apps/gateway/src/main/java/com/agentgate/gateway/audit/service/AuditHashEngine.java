package com.agentgate.gateway.audit.service;

import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditRecordDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cryptographic hashing engine for the tamper-evident audit log (FR7).
 * <p>
 * Ensures deterministic canonical JSON serialization and SHA-256 hash calculation
 * across all 16 business and chain linkage fields.
 */
@Service
public class AuditHashEngine {

    /**
     * Standard ISO-8601 UTC formatter with fixed 3 millisecond digits.
     * Guarantees bit-level identical string representations across JVM and database round-trips.
     */
    public static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper canonicalMapper;

    public AuditHashEngine() {
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Constructs canonical JSON string for an existing persisted AuditEvent.
     */
    public String computeCanonicalJson(AuditEvent event) {
        return buildCanonicalJson(
                event.sequenceNumber(),
                event.eventTimestamp(),
                event.eventType(),
                event.agentId(),
                event.userId(),
                event.actorId(),
                event.toolName(),
                event.redactedArgumentsJson(),
                event.policyRuleId(),
                event.policyRuleName(),
                event.decision(),
                event.approvalId(),
                event.outcome(),
                event.latencyMs(),
                event.errorMessage(),
                event.prevRecordHash()
        );
    }

    /**
     * Constructs canonical JSON string for an AuditRecordDraft with assigned sequence and prev-hash.
     */
    public String computeCanonicalJson(long sequenceNumber, String prevRecordHash, AuditRecordDraft draft) {
        return buildCanonicalJson(
                sequenceNumber,
                draft.eventTimestamp(),
                draft.eventType(),
                draft.agentId(),
                draft.userId(),
                draft.actorId(),
                draft.toolName(),
                draft.redactedArgumentsJson(),
                draft.policyRuleId(),
                draft.policyRuleName(),
                draft.decision(),
                draft.approvalId(),
                draft.outcome(),
                draft.latencyMs(),
                draft.errorMessage(),
                prevRecordHash
        );
    }

    /**
     * Builds canonical JSON with all 16 fields in sorted TreeMap order and explicit null values.
     */
    public String buildCanonicalJson(
            long sequenceNumber,
            Instant eventTimestamp,
            String eventType,
            String agentId,
            String userId,
            String actorId,
            String toolName,
            String redactedArgumentsJson,
            String policyRuleId,
            String policyRuleName,
            String decision,
            String approvalId,
            String outcome,
            long latencyMs,
            String errorMessage,
            String prevRecordHash
    ) {
        Map<String, Object> canonicalMap = new TreeMap<>();
        canonicalMap.put("actor_id", actorId);
        canonicalMap.put("agent_id", agentId);
        canonicalMap.put("approval_id", approvalId);
        canonicalMap.put("decision", decision);
        canonicalMap.put("error_message", errorMessage);
        canonicalMap.put("event_timestamp", eventTimestamp != null
                ? TIMESTAMP_FORMATTER.format(eventTimestamp.truncatedTo(ChronoUnit.MILLIS))
                : null);
        canonicalMap.put("event_type", eventType);
        canonicalMap.put("latency_ms", latencyMs);
        canonicalMap.put("outcome", outcome);
        canonicalMap.put("policy_rule_id", policyRuleId);
        canonicalMap.put("policy_rule_name", policyRuleName);
        canonicalMap.put("prev_record_hash", prevRecordHash);
        canonicalMap.put("redacted_arguments_json", redactedArgumentsJson != null ? redactedArgumentsJson : "{}");
        canonicalMap.put("sequence_number", sequenceNumber);
        canonicalMap.put("tool_name", toolName);
        canonicalMap.put("user_id", userId);

        try {
            return canonicalMapper.writeValueAsString(canonicalMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to construct canonical audit JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Computes the SHA-256 hex digest for a given canonical JSON string.
     */
    public String computeHash(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                hexString.append(Character.forDigit((b >> 4) & 0xF, 16));
                hexString.append(Character.forDigit(b & 0xF, 16));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Computes the record hash for an AuditRecordDraft.
     */
    public String computeRecordHash(long sequenceNumber, String prevRecordHash, AuditRecordDraft draft) {
        String canonicalJson = computeCanonicalJson(sequenceNumber, prevRecordHash, draft);
        return computeHash(canonicalJson);
    }

    /**
     * Computes the record hash for an existing AuditEvent.
     */
    public String computeRecordHash(AuditEvent event) {
        String canonicalJson = computeCanonicalJson(event);
        return computeHash(canonicalJson);
    }

    /**
     * Serializes a scrubbed argument Map into deterministic canonical JSON with recursively sorted keys.
     */
    public String serializeCanonicalArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sortedMap = sortMapRecursively(arguments);
        try {
            return canonicalMapper.writeValueAsString(sortedMap);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sortMapRecursively(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> subMap) {
                sorted.put(entry.getKey(), sortMapRecursively((Map<String, Object>) subMap));
            } else if (val instanceof List<?> list) {
                sorted.put(entry.getKey(), sortListRecursively(list));
            } else {
                sorted.put(entry.getKey(), val);
            }
        }
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private List<?> sortListRecursively(List<?> list) {
        if (list == null) return null;
        return list.stream().map(item -> {
            if (item instanceof Map<?, ?> subMap) {
                return sortMapRecursively((Map<String, Object>) subMap);
            } else if (item instanceof List<?> subList) {
                return sortListRecursively(subList);
            } else {
                return item;
            }
        }).toList();
    }
}
