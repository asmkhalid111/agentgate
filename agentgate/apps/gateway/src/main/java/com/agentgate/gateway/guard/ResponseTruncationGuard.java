package com.agentgate.gateway.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Guard that enforces response size limits on downstream tool outputs (FR5).
 * Truncates oversized JSON objects, arrays, and text to prevent memory exhaustion
 * and LLM context overflow, while preserving valid JSON syntax and providing an explicit notice.
 */
@Component
public class ResponseTruncationGuard {

    private static final Logger log = LoggerFactory.getLogger(ResponseTruncationGuard.class);

    public static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536; // 64 KB
    public static final int DEFAULT_MAX_ARRAY_ITEMS = 100;
    public static final String TRUNCATION_NOTICE_KEY = "_truncationNotice";
    public static final String TRUNCATION_NOTICE_MESSAGE =
            "TRUNCATED: Response exceeded maximum allowed limit. Data was safely cut to protect token and memory limits.";

    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;
    private final int maxArrayItems;

    @Autowired
    public ResponseTruncationGuard(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_ARRAY_ITEMS);
    }

    public ResponseTruncationGuard(ObjectMapper objectMapper, int maxResponseBytes, int maxArrayItems) {
        this.objectMapper = objectMapper;
        this.maxResponseBytes = maxResponseBytes;
        this.maxArrayItems = maxArrayItems;
    }

    /**
     * Inspects and safely truncates response data if it exceeds configured byte or array limits.
     * All agents (including high-clearance) are subject to size restrictions.
     *
     * @param data downstream response data (Map, List, String, or POJO)
     * @return bounded response data with explicit truncation notice if shortened
     */
    public Object truncate(Object data) {
        if (data == null) {
            return null;
        }

        // 1. Plain text or serialized JSON string
        if (data instanceof String str) {
            return truncateString(str);
        }

        // 2. Structured JSON Data (List, Map, POJO)
        try {
            byte[] rawBytes = objectMapper.writeValueAsBytes(data);
            if (isWithinLimits(data, rawBytes.length)) {
                return data;
            }

            return truncateStructuredData(data, rawBytes.length);
        } catch (Exception e) {
            log.warn("Failed to inspect response size; returning original data: {}", e.getMessage());
            return data;
        }
    }

    private boolean isWithinLimits(Object data, int byteSize) {
        if (byteSize > maxResponseBytes) {
            return false;
        }
        if (data instanceof Collection<?> col) {
            return col.size() <= maxArrayItems;
        }
        if (data instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof Collection<?> nestedCol && nestedCol.size() > maxArrayItems) {
                    return false;
                }
            }
        }
        return true;
    }

    private Object truncateString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        String trimmed = str.trim();
        boolean isJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));

        if (isJson) {
            try {
                Object parsed = objectMapper.readValue(str, Object.class);
                if (isWithinLimits(parsed, bytes.length)) {
                    return str;
                }

                Object truncated = truncateStructuredData(parsed, bytes.length);
                return objectMapper.writeValueAsString(truncated);
            } catch (Exception e) {
                // If not valid JSON despite braces, treat as plain text
                return truncateText(str, maxResponseBytes);
            }
        } else {
            return truncateText(str, maxResponseBytes);
        }
    }

    private Object truncateStructuredData(Object data, int currentByteSize) {
        if (data instanceof List<?> list) {
            return truncateList(list);
        }

        if (data instanceof Map<?, ?> map) {
            return truncateMap(map, currentByteSize);
        }

        try {
            JsonNode node = objectMapper.valueToTree(data);
            if (node.isArray()) {
                List<?> list = objectMapper.convertValue(node, List.class);
                return truncateList(list);
            } else if (node.isObject()) {
                Map<String, Object> map = objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
                return truncateMap(map, currentByteSize);
            }
        } catch (Exception e) {
            log.warn("Error converting node for truncation: {}", e.getMessage());
        }

        return Map.of(
                TRUNCATION_NOTICE_KEY, TRUNCATION_NOTICE_MESSAGE,
                "status", "TRUNCATED"
        );
    }

    private List<Object> truncateList(List<?> list) {
        int initialTake = Math.min(list.size(), Math.max(0, maxArrayItems - 1));
        List<Object> result = new ArrayList<>(list.subList(0, initialTake));

        String noticeMessage = list.size() > maxArrayItems
                ? "TRUNCATED: Array exceeded maximum limit of " + maxArrayItems + " items. Subsequent items were omitted."
                : "TRUNCATED: Array exceeded maximum allowed size of " + maxResponseBytes + " bytes. Subsequent items were omitted.";
        Map<String, Object> notice = Map.of(TRUNCATION_NOTICE_KEY, noticeMessage);
        result.add(notice);

        // Verify byte size and trim further if necessary to fit within maxResponseBytes
        try {
            while (result.size() > 1 && objectMapper.writeValueAsBytes(result).length > maxResponseBytes) {
                result.remove(result.size() - 2); // remove last content item, keeping notice at the end
            }
            if (objectMapper.writeValueAsBytes(result).length > maxResponseBytes) {
                return List.of(notice);
            }
        } catch (Exception e) {
            log.warn("Error checking byte size of truncated list: {}", e.getMessage());
        }

        return result;
    }

    private Map<String, Object> truncateMap(Map<?, ?> map, int currentByteSize) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        // Attempt 1: Truncate any nested arrays or large string values within the map
        for (Map.Entry<String, Object> entry : copy.entrySet()) {
            if (entry.getValue() instanceof List<?> nestedList) {
                if (nestedList.size() > maxArrayItems) {
                    entry.setValue(truncateList(nestedList));
                }
            } else if (entry.getValue() instanceof String strVal) {
                byte[] strBytes = strVal.getBytes(StandardCharsets.UTF_8);
                if (strBytes.length > maxResponseBytes / 2) {
                    entry.setValue(truncateText(strVal, maxResponseBytes / 2));
                }
            }
        }

        copy.put(TRUNCATION_NOTICE_KEY, TRUNCATION_NOTICE_MESSAGE);
        copy.put("status", "TRUNCATED");

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(copy);
            if (bytes.length <= maxResponseBytes) {
                return copy;
            }
        } catch (Exception e) {
            log.warn("Error calculating map byte size: {}", e.getMessage());
        }

        // Fallback for unusual or monolithic JSON objects that cannot be reduced cleanly:
        // Return a compact valid JSON truncation notice object (Reviewer Correction A)
        Map<String, Object> minimalNotice = new LinkedHashMap<>();
        minimalNotice.put(TRUNCATION_NOTICE_KEY, TRUNCATION_NOTICE_MESSAGE);
        minimalNotice.put("status", "TRUNCATED");
        minimalNotice.put("originalSizeEstimateBytes", currentByteSize);
        return minimalNotice;
    }

    public String truncateText(String text, int byteLimit) {
        if (text == null) return null;
        byte[] originalBytes = text.getBytes(StandardCharsets.UTF_8);
        if (originalBytes.length <= byteLimit) {
            return text;
        }

        String notice = "\n\n[TRUNCATED: Response exceeded maximum allowed size of " + byteLimit + " bytes]";
        byte[] noticeBytes = notice.getBytes(StandardCharsets.UTF_8);
        if (noticeBytes.length > byteLimit) {
            return "[TRUNCATED]".substring(0, Math.min(11, byteLimit));
        }

        int targetContentBytes = Math.max(0, byteLimit - noticeBytes.length);

        int charLimit = Math.min(text.length(), targetContentBytes);
        while (charLimit > 0) {
            // Avoid ending in the middle of a high surrogate
            if (Character.isHighSurrogate(text.charAt(charLimit - 1))) {
                charLimit--;
            }
            String candidate = text.substring(0, charLimit);
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= targetContentBytes) {
                return candidate + notice;
            }
            charLimit--;
        }
        return notice;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public int getMaxArrayItems() {
        return maxArrayItems;
    }
}
