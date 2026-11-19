package com.agentgate.gateway.guard;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PiiRedactionGuard Test Suite (FR5)")
class PiiRedactionGuardTest {

    private PiiRedactionGuard guard;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        guard = new PiiRedactionGuard(objectMapper);
    }

    private ToolExecutionContext createContext(Set<String> roles) {
        return new ToolExecutionContext(
                "test-agent",
                "test_tool",
                Collections.emptyMap(),
                null,
                RiskLevel.LOW,
                roles
        );
    }

    @Test
    @DisplayName("1. Email addresses must be masked with [REDACTED_EMAIL]")
    void emailMasking_StandardAgent() {
        Map<String, Object> data = Map.of(
                "customerEmail", "alice.smith+promo@example.com",
                "supportEmail", "help@bank.com.bd"
        );
        ToolExecutionContext ctx = createContext(Set.of("ROLE_AGENT"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(data, ctx);

        assertEquals("[REDACTED_EMAIL]", result.get("customerEmail"));
        assertEquals("[REDACTED_EMAIL]", result.get("supportEmail"));
    }

    @Test
    @DisplayName("2. Bangladesh mobile numbers in 01..., 8801..., and +8801... formats must be masked with [REDACTED_PHONE]")
    void bangladeshMobileFormats_StandardAgent() {
        Map<String, Object> data = Map.of(
                "phoneLocal", "01712345678",
                "phoneCountryCode", "8801812345678",
                "phonePlusCountryCode", "+8801912345678",
                "foreignPhone", "+14155552671", // should not be masked as BD phone
                "shortNumber", "12345678"        // should not be masked as BD phone
        );
        ToolExecutionContext ctx = createContext(Set.of("ROLE_USER"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(data, ctx);

        assertEquals("[REDACTED_PHONE]", result.get("phoneLocal"));
        assertEquals("[REDACTED_PHONE]", result.get("phoneCountryCode"));
        assertEquals("[REDACTED_PHONE]", result.get("phonePlusCountryCode"));
        assertEquals("+14155552671", result.get("foreignPhone"));
        assertEquals("12345678", result.get("shortNumber"));
    }

    @Test
    @DisplayName("3. String NIDs of 10, 13, and 17 digits must be masked with [REDACTED_NID]")
    void stringNidFormats_StandardAgent() {
        Map<String, Object> data = Map.of(
                "nid10", "1234567890",
                "nid13", "1234567890123",
                "nid17", "19901234567890123",
                "nonNid12", "123456789012",      // 12 digits - not NID
                "nonNid5", "12345"               // 5 digits - not NID
        );
        ToolExecutionContext ctx = createContext(Set.of("ROLE_AGENT"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(data, ctx);

        assertEquals("[REDACTED_NID]", result.get("nid10"));
        assertEquals("[REDACTED_NID]", result.get("nid13"));
        assertEquals("[REDACTED_NID]", result.get("nid17"));
        assertEquals("123456789012", result.get("nonNid12"));
        assertEquals("12345", result.get("nonNid5"));
    }

    @Test
    @DisplayName("4. Numeric NID fields must be redacted without masking unrelated numeric fields like amounts or prices")
    void numericNidFields_WithoutMaskingUnrelatedNumbers() {
        Map<String, Object> data = Map.of(
                "nid", 1234567890L,                     // 10-digit numeric NID
                "national_id", 19901234567890123L,       // 17-digit numeric NID
                "amount", 1234567890L,                  // 10-digit numeric amount (must NOT be masked!)
                "orderTotal", 2500,                     // regular numeric amount (must NOT be masked!)
                "itemCount", 3                          // quantity (must NOT be masked!)
        );
        ToolExecutionContext ctx = createContext(Set.of("ROLE_AGENT"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(data, ctx);

        assertEquals("[REDACTED_NID]", result.get("nid"));
        assertEquals("[REDACTED_NID]", result.get("national_id"));
        assertEquals(1234567890L, ((Number) result.get("amount")).longValue(), "Amount must remain numeric and unmasked");
        assertEquals(2500, ((Number) result.get("orderTotal")).intValue(), "Order total must remain numeric and unmasked");
        assertEquals(3, ((Number) result.get("itemCount")).intValue(), "Item count must remain numeric and unmasked");
    }

    @Test
    @DisplayName("5. Deeply nested JSON structures and arrays must have all PII redacted while preserving structure")
    void nestedObjectsAndArrays_StandardAgent() {
        Map<String, Object> customer = Map.of(
                "name", "Rahim Ahmed",
                "contacts", List.of(
                        Map.of("type", "mobile", "value", "+8801711223344"),
                        Map.of("type", "email", "value", "rahim@company.bd")
                ),
                "identity", Map.of(
                        "nid", "1234567890",
                        "status", "VERIFIED"
                ),
                "accountBalance", 50000.75
        );
        ToolExecutionContext ctx = createContext(Set.of("ROLE_AGENT"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(customer, ctx);

        assertEquals("Rahim Ahmed", result.get("name"));
        assertEquals(50000.75, result.get("accountBalance"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) result.get("contacts");
        assertEquals(2, contacts.size());
        assertEquals("[REDACTED_PHONE]", contacts.get(0).get("value"));
        assertEquals("[REDACTED_EMAIL]", contacts.get(1).get("value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) result.get("identity");
        assertEquals("[REDACTED_NID]", identity.get("nid"));
        assertEquals("VERIFIED", identity.get("status"));
    }

    @Test
    @DisplayName("6. Explicit HIGH_CLEARANCE agent receives 100% unredacted raw output")
    void highClearanceAgent_ReceivesOriginalOutput() {
        Map<String, Object> sensitiveData = Map.of(
                "email", "ceo@agentgate.com",
                "phone", "01712345678",
                "nid", "19901234567890123"
        );
        ToolExecutionContext highClearanceCtx1 = createContext(Set.of("HIGH_CLEARANCE"));
        ToolExecutionContext highClearanceCtx2 = createContext(Set.of("ROLE_HIGH_CLEARANCE"));

        @SuppressWarnings("unchecked")
        Map<String, Object> res1 = (Map<String, Object>) guard.redact(sensitiveData, highClearanceCtx1);
        @SuppressWarnings("unchecked")
        Map<String, Object> res2 = (Map<String, Object>) guard.redact(sensitiveData, highClearanceCtx2);

        assertEquals("ceo@agentgate.com", res1.get("email"));
        assertEquals("01712345678", res1.get("phone"));
        assertEquals("19901234567890123", res1.get("nid"));

        assertEquals("ceo@agentgate.com", res2.get("email"));
        assertEquals("01712345678", res2.get("phone"));
        assertEquals("19901234567890123", res2.get("nid"));
    }

    @Test
    @DisplayName("7. ADMIN without HIGH_CLEARANCE still receives redacted output (Correction A)")
    void adminWithoutHighClearance_ReceivesRedactedOutput() {
        Map<String, Object> sensitiveData = Map.of(
                "email", "ceo@agentgate.com",
                "phone", "01712345678",
                "nid", "19901234567890123"
        );
        ToolExecutionContext adminCtx = createContext(Set.of("ROLE_ADMIN", "ADMIN", "SCOPE_tool:admin"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) guard.redact(sensitiveData, adminCtx);

        assertEquals("[REDACTED_EMAIL]", result.get("email"), "Admin without HIGH_CLEARANCE must be redacted");
        assertEquals("[REDACTED_PHONE]", result.get("phone"), "Admin without HIGH_CLEARANCE must be redacted");
        assertEquals("[REDACTED_NID]", result.get("nid"), "Admin without HIGH_CLEARANCE must be redacted");
    }

    @Test
    @DisplayName("8. Serialized JSON string output remains valid JSON and has sensitive fields redacted")
    void serializedJsonString_RemainsValidAndRedacted() throws Exception {
        String jsonPayload = """
                {
                  "orderId": "ORD-123",
                  "customer": "Karim",
                  "email": "karim@shop.bd",
                  "phone": "+8801812345678",
                  "total": 350.0
                }
                """;
        ToolExecutionContext ctx = createContext(Set.of("ROLE_AGENT"));

        Object result = guard.redact(jsonPayload, ctx);
        assertTrue(result instanceof String, "Serialized JSON string input should return serialized JSON string");

        String resultStr = (String) result;
        Map<String, Object> parsed = objectMapper.readValue(resultStr, new TypeReference<>() {});

        assertEquals("ORD-123", parsed.get("orderId"));
        assertEquals("Karim", parsed.get("customer"));
        assertEquals("[REDACTED_EMAIL]", parsed.get("email"));
        assertEquals("[REDACTED_PHONE]", parsed.get("phone"));
        assertEquals(350.0, ((Number) parsed.get("total")).doubleValue());
    }

    @Test
    @DisplayName("9. Agent-visible error messages cannot leak sensitive data (Correction D)")
    void agentVisibleErrorMessages_CannotLeakSensitiveData() {
        String rawError = "Failed to charge customer rahim@example.com (phone: 01712345678, NID: 1234567890): gateway timeout";

        String sanitized = guard.redactString(rawError);

        assertEquals("Failed to charge customer [REDACTED_EMAIL] (phone: [REDACTED_PHONE], NID: [REDACTED_NID]): gateway timeout", sanitized);
        assertFalse(sanitized.contains("rahim@example.com"));
        assertFalse(sanitized.contains("01712345678"));
        assertFalse(sanitized.contains("1234567890"));
    }
}
