package com.agentgate.gateway.guard;

import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonSchemaValidationGuard Test Suite (FR5)")
class JsonSchemaValidationGuardTest {

    private JsonSchemaValidationGuard guard;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        guard = new JsonSchemaValidationGuard(objectMapper);
    }

    private RegisteredTool createTool(String name, String inputSchema) {
        return new RegisteredTool(
                "tool-" + name,
                name,
                "Description for " + name,
                inputSchema,
                RiskLevel.LOW,
                "Core Team",
                "http://localhost:8082/api/" + name,
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. Valid arguments adhering to schema must pass validation")
    void validArguments_PassesValidation() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" },
                    "amount": { "type": "number" }
                  },
                  "required": ["orderId", "amount"]
                }
                """;
        RegisteredTool tool = createTool("orders_refund", schema);
        Map<String, Object> arguments = Map.of("orderId", "ORD-101", "amount", 250.5);

        ValidationResult result = guard.validate(tool, arguments);

        assertTrue(result.valid(), "Expected validation to succeed for valid arguments");
        assertNull(result.error());
    }

    @Test
    @DisplayName("2. Missing required property must be rejected with descriptive error")
    void missingRequiredProperty_Rejected() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" },
                    "amount": { "type": "number" }
                  },
                  "required": ["orderId", "amount"]
                }
                """;
        RegisteredTool tool = createTool("orders_refund", schema);
        Map<String, Object> arguments = Map.of("amount", 250.5); // missing orderId

        ValidationResult result = guard.validate(tool, arguments);

        assertFalse(result.valid(), "Expected validation to fail for missing required property");
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("SCHEMA_VALIDATION_FAILED:"), "Error prefix mismatch");
        assertTrue(result.error().contains("orderId"), "Error should mention missing property name 'orderId'");
    }

    @Test
    @DisplayName("3. Wrong argument type (string instead of number) must be rejected")
    void wrongArgumentType_Rejected() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" },
                    "amount": { "type": "number" }
                  },
                  "required": ["orderId", "amount"]
                }
                """;
        RegisteredTool tool = createTool("orders_refund", schema);
        Map<String, Object> arguments = Map.of("orderId", "ORD-101", "amount", "not-a-number");

        ValidationResult result = guard.validate(tool, arguments);

        assertFalse(result.valid(), "Expected validation to fail for type mismatch");
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("SCHEMA_VALIDATION_FAILED:"));
    }

    @Test
    @DisplayName("4. Empty arguments must be rejected when required properties exist")
    void emptyArguments_RejectedWhenRequiredPropertiesExist() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" }
                  },
                  "required": ["orderId"]
                }
                """;
        RegisteredTool tool = createTool("orders_get", schema);

        ValidationResult result1 = guard.validate(tool, Collections.emptyMap());
        ValidationResult result2 = guard.validate(tool, null);

        assertFalse(result1.valid(), "Empty map should fail when required properties exist");
        assertFalse(result2.valid(), "Null map should fail when required properties exist");
        assertTrue(result1.error().contains("orderId"));
    }

    @Test
    @DisplayName("5. Explicit unconstrained schema {} must accept arbitrary valid arguments")
    void explicitUnconstrainedSchema_AcceptsAnyArguments() {
        RegisteredTool toolEmpty = createTool("custom_tool_empty", "{}");
        RegisteredTool toolObj = createTool("custom_tool_obj", "{\"type\":\"object\"}");

        ValidationResult res1 = guard.validate(toolEmpty, Map.of("foo", "bar", "num", 42));
        ValidationResult res2 = guard.validate(toolObj, Map.of("nested", Map.of("k", "v")));
        ValidationResult res3 = guard.validate(toolEmpty, Collections.emptyMap());

        assertTrue(res1.valid(), "Empty schema should accept arbitrary arguments");
        assertTrue(res2.valid(), "Unconstrained object schema should accept arbitrary arguments");
        assertTrue(res3.valid(), "Empty schema should accept empty arguments");
    }

    @Test
    @DisplayName("6. Malformed JSON schema must fail safely without uncaught exception")
    void malformedSchema_FailsSafely() {
        RegisteredTool tool = createTool("broken_tool", "{invalid-json-schema::");

        ValidationResult result = guard.validate(tool, Map.of("k", "v"));

        assertFalse(result.valid(), "Malformed schema must be rejected");
        assertTrue(result.error().startsWith("SCHEMA_VALIDATION_FAILED: Malformed input schema"));
    }

    @Test
    @DisplayName("7. Missing schema on regular registered tool must be rejected")
    void missingSchema_RegularTool_Rejected() {
        RegisteredTool toolNull = createTool("new_production_tool", null);
        RegisteredTool toolBlank = createTool("new_production_tool_blank", "   ");

        ValidationResult res1 = guard.validate(toolNull, Map.of("k", "v"));
        ValidationResult res2 = guard.validate(toolBlank, Map.of("k", "v"));

        assertFalse(res1.valid(), "Null schema on new tool must be rejected");
        assertFalse(res2.valid(), "Blank schema on new tool must be rejected");
        assertTrue(res1.error().contains("no registered input schema"));
    }

    @Test
    @DisplayName("8. Missing schema on legacy M01 baseline fixture is accepted via compatibility rule")
    void missingSchema_LegacyM01Fixture_Accepted() {
        RegisteredTool legacyTool = createTool("m01_status", null);

        ValidationResult result = guard.validate(legacyTool, Map.of("msg", "ping"));

        assertTrue(result.valid(), "M01 legacy fixture with missing schema should be accepted");
    }
}
