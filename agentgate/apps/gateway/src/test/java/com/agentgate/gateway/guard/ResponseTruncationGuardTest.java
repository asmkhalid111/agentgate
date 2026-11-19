package com.agentgate.gateway.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResponseTruncationGuard Unit Tests (FR5)")
class ResponseTruncationGuardTest {

    private ObjectMapper objectMapper;
    private ResponseTruncationGuard guard;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        guard = new ResponseTruncationGuard(objectMapper);
    }

    @Test
    @DisplayName("1. Normal response within byte and item limits is returned untouched without notice")
    void normalResponse_withinLimits_returnedUntouched() {
        Map<String, Object> normalData = Map.of(
                "orderId", "ORD-12345",
                "status", "COMPLETED",
                "items", List.of("item-1", "item-2", "item-3")
        );

        Object result = guard.truncate(normalData);

        assertSame(normalData, result, "Should return exact original object if within limits");
        assertFalse(normalData.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }

    @Test
    @DisplayName("2. Normal array within item limit (<= 100) is returned untouched")
    void normalArray_withinLimit_returnedUntouched() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add("item-" + i);
        }

        Object result = guard.truncate(items);

        assertSame(items, result, "Should return original list if <= 100 items and <= 64KB");
        assertEquals(50, ((List<?>) result).size());
    }

    @Test
    @DisplayName("3. Oversized array (> 100 items) is truncated to strictly 100 items (99 items + 1 notice)")
    @SuppressWarnings("unchecked")
    void oversizedArray_truncatedToExactly100ItemsIncludingNotice() {
        List<Map<String, Object>> largeList = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            largeList.add(Map.of("index", i, "data", "row-" + i));
        }

        Object result = guard.truncate(largeList);

        assertNotNull(result);
        assertTrue(result instanceof List<?>);
        List<Object> truncated = (List<Object>) result;

        // Reviewer requirement: array total items must strictly respect the 100-item ceiling
        assertEquals(100, truncated.size(), "Total items must be strictly 100 (99 content + 1 notice)");

        // Verify content items (first 99)
        for (int i = 0; i < 99; i++) {
            Map<String, Object> item = (Map<String, Object>) truncated.get(i);
            assertEquals(i, item.get("index"));
        }

        // Verify 100th item is the explicit truncation notice
        Map<String, Object> lastItem = (Map<String, Object>) truncated.get(99);
        assertTrue(lastItem.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY), "Last item must contain truncation notice");
        assertTrue(String.valueOf(lastItem.get(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY)).contains("Array exceeded maximum limit of 100 items"));
    }

    @Test
    @DisplayName("4. Oversized array exceeding byte limit is trimmed further to fit strictly within 64 KB")
    void oversizedArray_exceedingByteLimit_trimmedWithinByteCeiling() throws Exception {
        // Create 80 items where each item is 1 KB (total ~80 KB > 64 KB, but items <= 100)
        List<Map<String, Object>> heavyList = new ArrayList<>();
        String largeString = "A".repeat(1024);
        for (int i = 0; i < 80; i++) {
            heavyList.add(Map.of("id", i, "payload", largeString));
        }

        Object result = guard.truncate(heavyList);

        assertNotNull(result);
        assertTrue(result instanceof List<?>);
        List<?> truncated = (List<?>) result;

        assertTrue(truncated.size() <= 100, "Items must be <= 100");
        byte[] serializedBytes = objectMapper.writeValueAsBytes(truncated);
        assertTrue(serializedBytes.length <= ResponseTruncationGuard.DEFAULT_MAX_RESPONSE_BYTES,
                "Serialized bytes (" + serializedBytes.length + ") must not exceed 65536 bytes");

        // Verify notice is present at the end
        Object last = truncated.get(truncated.size() - 1);
        assertTrue(last instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) last).containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }

    @Test
    @DisplayName("5. Map with oversized nested array has array truncated and notice injected")
    @SuppressWarnings("unchecked")
    void mapWithOversizedArray_truncatesArrayAndInjectsNotice() {
        List<String> bigArray = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            bigArray.add("element-" + i);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("transactionId", "TX-999");
        map.put("logs", bigArray);

        Object result = guard.truncate(map);

        assertNotNull(result);
        assertTrue(result instanceof Map<?, ?>);
        Map<String, Object> truncatedMap = (Map<String, Object>) result;

        assertTrue(truncatedMap.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
        List<?> truncatedLogs = (List<?>) truncatedMap.get("logs");
        assertEquals(100, truncatedLogs.size(), "Nested array must be truncated to 100 items");
    }

    @Test
    @DisplayName("6. Map with large string field has string safely truncated while preserving map keys")
    @SuppressWarnings("unchecked")
    void mapWithLargeString_truncatesStringAndPreservesKeys() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("query", "SELECT * FROM huge_audit_log");
        map.put("dump", "X".repeat(80_000));

        Object result = guard.truncate(map);

        assertNotNull(result);
        assertTrue(result instanceof Map<?, ?>);
        Map<String, Object> truncatedMap = (Map<String, Object>) result;

        assertTrue(truncatedMap.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
        assertEquals("TRUNCATED", truncatedMap.get("status"));
        assertEquals("SELECT * FROM huge_audit_log", truncatedMap.get("query"));
        assertTrue(String.valueOf(truncatedMap.get("dump")).contains("[TRUNCATED"));

        byte[] serializedBytes = objectMapper.writeValueAsBytes(truncatedMap);
        assertTrue(serializedBytes.length <= ResponseTruncationGuard.DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Test
    @DisplayName("6b. Monolithic oversized JSON map (> 64 KB) that cannot be reduced returns minimal notice object")
    void monolithicOversizedMap_cannotBeReduced_returnsMinimalNoticeObject() throws Exception {
        // 4,000 key-value pairs (~240 KB > 64 KB), no arrays and no single string > 32 KB
        Map<String, Object> monolithicMap = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            monolithicMap.put("key_long_attribute_name_" + i, "value_long_attribute_value_" + i);
        }

        Object result = guard.truncate(monolithicMap);

        assertNotNull(result);
        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> noticeMap = (Map<?, ?>) result;

        assertTrue(noticeMap.containsKey(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
        assertEquals("TRUNCATED", noticeMap.get("status"));
        assertTrue(noticeMap.containsKey("originalSizeEstimateBytes"));

        // Must strictly fit within byte ceiling
        byte[] serializedBytes = objectMapper.writeValueAsBytes(noticeMap);
        assertTrue(serializedBytes.length <= ResponseTruncationGuard.DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Test
    @DisplayName("7. Serialized JSON array string (> 100 items) is truncated to valid JSON array string")
    void serializedJsonArrayString_truncatedToValidJsonArrayString() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            list.add(Map.of("id", i));
        }
        String serialized = objectMapper.writeValueAsString(list);

        Object result = guard.truncate(serialized);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String truncatedStr = (String) result;

        // Must be valid JSON parseable by Jackson
        JsonNode node = objectMapper.readTree(truncatedStr);
        assertTrue(node.isArray(), "Output must parse back to valid JSON array");
        assertEquals(100, node.size(), "Parsed array must have exactly 100 items");
        assertTrue(node.get(99).has(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }

    @Test
    @DisplayName("8. Serialized JSON object string (> 64 KB) is truncated to valid JSON object string")
    void serializedJsonObjectString_truncatedToValidJsonObjectString() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("key", "VAL");
        map.put("giantField", "Z".repeat(70_000));
        String serialized = objectMapper.writeValueAsString(map);

        Object result = guard.truncate(serialized);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String truncatedStr = (String) result;

        byte[] utf8Bytes = truncatedStr.getBytes(StandardCharsets.UTF_8);
        assertTrue(utf8Bytes.length <= ResponseTruncationGuard.DEFAULT_MAX_RESPONSE_BYTES,
                "UTF-8 bytes (" + utf8Bytes.length + ") must not exceed 65536");

        // Must be valid JSON parseable by Jackson
        JsonNode node = objectMapper.readTree(truncatedStr);
        assertTrue(node.isObject(), "Output must parse back to valid JSON object");
        assertTrue(node.has(ResponseTruncationGuard.TRUNCATION_NOTICE_KEY));
    }

    @Test
    @DisplayName("9. Plain text within byte limit is returned untouched")
    void plainText_withinLimit_returnedUntouched() {
        String normalText = "Database query returned 10 rows successfully.";
        Object result = guard.truncate(normalText);
        assertEquals(normalText, result);
    }

    @Test
    @DisplayName("10. Plain text (> 64 KB) is truncated with notice strictly within 65,536 UTF-8 bytes")
    void plainText_oversized_truncatedStrictlyWithinByteLimit() {
        String hugeText = "Line content here: " + "A".repeat(80_000);
        Object result = guard.truncate(hugeText);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String truncated = (String) result;

        byte[] utf8Bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(utf8Bytes.length <= ResponseTruncationGuard.DEFAULT_MAX_RESPONSE_BYTES,
                "UTF-8 byte length (" + utf8Bytes.length + ") must not exceed 65536 bytes");
        assertTrue(truncated.contains("[TRUNCATED: Response exceeded maximum allowed size of 65536 bytes]"));
    }

    @Test
    @DisplayName("11. Unicode multibyte characters and emojis are not corrupted across truncation boundary")
    void unicodeMultibyte_notCorruptedAcrossBoundary() {
        // Using custom small guard limit to test exact boundary handling
        ResponseTruncationGuard customGuard = new ResponseTruncationGuard(objectMapper, 150, 10);

        // Bengali text + emoji: "বাংলাদেশ 🇧🇩 привет 🌍" repeated
        String complexUnicode = "বাংলাদেশ 🇧🇩 привет 🌍 ".repeat(20);

        Object result = customGuard.truncate(complexUnicode);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String truncated = (String) result;

        byte[] utf8Bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(utf8Bytes.length <= 150, "Must be <= 150 bytes, actual: " + utf8Bytes.length);
        assertTrue(truncated.contains("[TRUNCATED"));
    }

    @Test
    @DisplayName("12. Null data returns null")
    void nullData_returnsNull() {
        assertNull(guard.truncate(null));
    }
}
