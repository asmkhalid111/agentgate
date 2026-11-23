package com.agentgate.gateway.guard;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Clearance-aware output PII redaction guard (FR5).
 * Redacts email addresses, Bangladeshi mobile phone numbers, and Bangladeshi NID numbers
 * before responses or error messages reach unauthorized calling agents.
 *
 * <p><b>Numeric Ambiguity and Context-Sensitive NID Redaction:</b></p>
 * <p>
 * Bangladeshi National ID (NID) numbers comprise 10 digits (Smart NID), 13 digits (legacy NID),
 * or 17 digits (birth-year prefixed legacy NID). A naive global regex matching any 10-digit sequence
 * introduces unacceptable false-positive redactions on business identifiers such as:
 * <ul>
 *   <li>Unix epoch timestamps (e.g., 1732085139 representing November 2024 dates)</li>
 *   <li>Order reference numbers (e.g., ORD-1732085139)</li>
 *   <li>Refund reference codes and transaction sequence numbers (e.g., REF-1732085139012)</li>
 *   <li>Monetary amounts, quantities, and balances</li>
 * </ul>
 * Because a 10-digit integer is statistically indistinguishable from current Unix epoch seconds,
 * raw unlabeled numbers cannot be universally redacted without corrupting legitimate business payloads.
 * Therefore, AgentGate enforces context-sensitive NID redaction:
 * <ol>
 *   <li><b>Recognized NID Fields:</b> Fields explicitly denoting NID identity (e.g., {@code nid},
 *       {@code national_id}, {@code userNid}, {@code customerNid}, etc.) are redacted whether textual or numeric.</li>
 *   <li><b>Explicitly Labeled Free Text:</b> In unstructured text, error messages, or unclassified fields,
 *       NID values are redacted only when preceded by contextual labels (e.g., "NID: 1234567890",
 *       "National ID: 1234567890123", "Voter ID: ...").</li>
 *   <li><b>Unlabeled Text & Fields:</b> Business identifiers (e.g., "ORD-1732085139") and numeric timestamps
 *       remain untouched, eliminating false positives.</li>
 * </ol>
 * Mobile phone numbers (which feature distinctive Bangladeshi prefixes 01[3-9] or +8801[3-9])
 * and email addresses continue to be matched and redacted globally.
 */
@Component
public class PiiRedactionGuard {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionGuard.class);

    // Bangladeshi Mobile Phone regex: 013-019 (11 digits), 8801... (13 digits), or +8801... (14 characters)
    // Uses lookaround boundaries to avoid partial matches on longer digit sequences
    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?8801|01)[3-9]\\d{8}(?!\\d)");

    // Bangladeshi National ID (NID) regex: 10-digit (Smart), 13-digit (Legacy), 17-digit (Birth-year prefixed)
    // Used in recognized NID fields where the field identity is already established
    private static final Pattern BD_NID_PATTERN = Pattern.compile("(?<!\\d)(?:\\d{17}|\\d{13}|\\d{10})(?!\\d)");

    // Context-sensitive labeled NID regex for free text, error messages, and unclassified fields.
    // Matches NID values accompanied by explicit labels such as "NID: ...", "National ID: ...", "Voter ID: ...", "Citizen ID: ..."
    // Preserves the label prefix while redacting the numeric identifier.
    private static final Pattern LABELED_NID_PATTERN = Pattern.compile(
            "(?i)(\\b(?:nid|national[\\s_]*id|voter[\\s_]*id|citizen[\\s_]*id)(?:[\\s_]*(?:no\\.?|number|num|#))?\\s*[:=\\-\\s]\\s*)(?:\\d{17}|\\d{13}|\\d{10})(?!\\d)"
    );

    // Standard RFC-compliant Email address regex
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private final ObjectMapper objectMapper;

    public PiiRedactionGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Redacts sensitive PII from downstream response data unless the agent has explicit HIGH_CLEARANCE.
     *
     * @param data    the raw downstream tool response
     * @param context the execution context containing validated agent roles
     * @return redacted response data for low-clearance agents, or original data for high-clearance agents
     */
    public Object redact(Object data, ToolExecutionContext context) {
        if (data == null || (context != null && context.hasHighClearance())) {
            return data;
        }

        // Handle serialized JSON strings or plain text strings
        if (data instanceof String str) {
            String trimmed = str.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    JsonNode node = objectMapper.readTree(str);
                    JsonNode redactedNode = traverseAndRedact(node, null);
                    return objectMapper.writeValueAsString(redactedNode);
                } catch (Exception e) {
                    return redactString(str);
                }
            } else {
                return redactString(str);
            }
        }

        try {
            JsonNode root = objectMapper.valueToTree(data);
            JsonNode redactedRoot = traverseAndRedact(root, null);
            return objectMapper.treeToValue(redactedRoot, Object.class);
        } catch (Exception e) {
            log.warn("PII redaction traversal failed; falling back to original data: {}", e.getMessage());
            return data;
        }
    }

    /**
     * Redacts sensitive PII from an agent-visible error message string.
     *
     * @param input the error message
     * @return sanitized error message without exposed PII
     */
    public String redactString(String input) {
        return redactStringInField(null, input);
    }

    /**
     * Redacts sensitive PII from a string value within a specific named field context.
     * If the field name is a recognized NID field, NID numbers (10, 13, 17 digits) are redacted even without labels.
     *
     * @param fieldName the name of the field containing the string, or null if unclassified
     * @param input     the string value
     * @return sanitized string
     */
    public String redactStringInField(String fieldName, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // Apply phone redaction first
        String result = BD_PHONE_PATTERN.matcher(input).replaceAll("[REDACTED_PHONE]");
        // Context-sensitive NID redaction: in recognized NID fields, redact bare NID numbers;
        // in unclassified or free-text fields, redact only labeled NIDs
        if (isNidFieldName(fieldName)) {
            result = BD_NID_PATTERN.matcher(result).replaceAll("[REDACTED_NID]");
        } else {
            result = LABELED_NID_PATTERN.matcher(result).replaceAll("$1[REDACTED_NID]");
        }
        // Apply email redaction
        result = EMAIL_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]");
        return result;
    }

    private JsonNode traverseAndRedact(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return node;
        }

        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), traverseAndRedact(field.getValue(), field.getKey()));
            }
            return copy;
        }

        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, traverseAndRedact(copy.get(i), fieldName));
            }
            return copy;
        }

        if (node.isTextual()) {
            return new TextNode(redactStringInField(fieldName, node.asText()));
        }

        // Numeric NID values: only redact numbers if the field name explicitly denotes an NID
        if (node.isNumber() && isNidFieldName(fieldName)) {
            String numStr = node.asText();
            if (numStr.length() == 10 || numStr.length() == 13 || numStr.length() == 17) {
                return new TextNode("[REDACTED_NID]");
            }
        }

        // Leave all other non-textual nodes (integers, floats, booleans) unchanged
        return node;
    }

    public static boolean isNidFieldName(String fieldName) {
        if (fieldName == null) return false;
        String lower = fieldName.toLowerCase();
        String normalized = lower.replace("_", "").replace("-", "");
        if (normalized.equals("nid") || normalized.equals("nationalid") || normalized.equals("nidnumber")
                || normalized.equals("nationalidnumber") || normalized.equals("nidno")
                || normalized.equals("nids") || normalized.equals("voterid") || normalized.equals("citizenid")) {
            return true;
        }
        // Matches patterns like nid10, nid13, nid17, nid_..., nidValue
        if (lower.startsWith("nid") && (lower.length() == 3 || lower.startsWith("nid_") || lower.startsWith("nid-")
                || (lower.length() > 3 && (Character.isDigit(lower.charAt(3)) || Character.isUpperCase(fieldName.charAt(3)))))) {
            return true;
        }
        // Matches suffixes like userNid, customerNid, userNationalId
        if (lower.endsWith("nid") || lower.endsWith("nationalid") || lower.endsWith("_nid") || lower.endsWith("-nid") || lower.endsWith("nids")) {
            return true;
        }
        return false;
    }
}
