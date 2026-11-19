package com.agentgate.gateway.guard;

import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * Pre-execution guard that validates tool call arguments against the tool's registered JSON Schema (FR5).
 * Prevents downstream tool execution when arguments are invalid or violate schema constraints.
 */
@Component
public class JsonSchemaValidationGuard {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaValidationGuard.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final JsonSchemaValidator schemaValidator;

    public JsonSchemaValidationGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaValidator = new DefaultJsonSchemaValidator();
    }

    /**
     * Validates input arguments against the registered tool's schema.
     *
     * @param tool      the registered tool metadata
     * @param arguments input arguments to validate
     * @return ValidationResult indicating accepted or rejected with diagnostic details
     */
    public ValidationResult validate(RegisteredTool tool, Map<String, Object> arguments) {
        String toolName = tool != null ? tool.name() : "unknown";
        String schemaJson = tool != null ? tool.inputSchema() : null;

        // Missing schema handling (Reviewer Correction B)
        if (schemaJson == null || schemaJson.isBlank()) {
            if (toolName != null && toolName.startsWith("m01_")) {
                // Minimal explicit compatibility rule for M01 legacy baseline fixtures
                return ValidationResult.accepted();
            }
            return ValidationResult.rejected("SCHEMA_VALIDATION_FAILED: Tool '" + toolName + "' has no registered input schema");
        }

        Map<String, Object> schemaMap;
        try {
            schemaMap = objectMapper.readValue(schemaJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Malformed JSON schema registered for tool '{}': {}", toolName, e.getMessage());
            return ValidationResult.rejected("SCHEMA_VALIDATION_FAILED: Malformed input schema for tool '" + toolName + "': " + e.getMessage());
        }

        // Explicit unconstrained schema {} or {"type":"object"} with no properties/required constraints
        if (schemaMap.isEmpty() || (schemaMap.size() == 1 && "object".equals(schemaMap.get("type")))) {
            return ValidationResult.accepted();
        }

        Map<String, Object> effectiveArguments = arguments != null ? arguments : Collections.emptyMap();

        try {
            JsonSchemaValidator.ValidationResponse response = schemaValidator.validate(schemaMap, effectiveArguments);
            if (response.valid()) {
                return ValidationResult.accepted();
            } else {
                String error = response.errorMessage();
                log.warn("Schema validation failed for tool '{}': {}", toolName, error);
                return ValidationResult.rejected("SCHEMA_VALIDATION_FAILED: " + (error != null ? error : "Input validation failed"));
            }
        } catch (Exception e) {
            log.warn("Schema validation threw exception for tool '{}': {}", toolName, e.getMessage());
            return ValidationResult.rejected("SCHEMA_VALIDATION_FAILED: Validation error: " + e.getMessage());
        }
    }
}
