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
 */
@Component
public class PiiRedactionGuard {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionGuard.class);

    // Bangladeshi Mobile Phone regex: 013-019 (11 digits), 8801... (13 digits), or +8801... (14 characters)
    // Uses lookaround boundaries to avoid partial matches on longer digit sequences
    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?8801|01)[3-9]\\d{8}(?!\\d)");

    // Bangladeshi National ID (NID) regex: 10-digit (Smart), 13-digit (Legacy), 17-digit (Birth-year prefixed)
    private static final Pattern BD_NID_PATTERN = Pattern.compile("(?<!\\d)(?:\\d{17}|\\d{13}|\\d{10})(?!\\d)");

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
        if (input == null || input.isEmpty()) {
            return input;
        }
        // Apply phone redaction first, then NID, then email
        String result = BD_PHONE_PATTERN.matcher(input).replaceAll("[REDACTED_PHONE]");
        result = BD_NID_PATTERN.matcher(result).replaceAll("[REDACTED_NID]");
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
            return new TextNode(redactString(node.asText()));
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

    private static boolean isNidFieldName(String fieldName) {
        if (fieldName == null) return false;
        String normalized = fieldName.toLowerCase().replace("_", "").replace("-", "");
        return normalized.equals("nid") || normalized.equals("nationalid") || normalized.equals("nidnumber")
                || normalized.equals("nationalidnumber") || normalized.equals("nidno");
    }
}
