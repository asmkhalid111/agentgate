package com.agentgate.gateway.registry.service;

import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class OpenApiImporterService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiImporterService.class);
    private final RegisteredToolRepository repository;
    private final ObjectMapper objectMapper;

    public OpenApiImporterService(RegisteredToolRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Imports an OpenAPI 3.0 JSON specification and registers corresponding tools.
     *
     * @param openApiJsonString Raw JSON string of the OpenAPI document
     * @param defaultBaseUrl    Base URL for endpoints if servers block is empty
     * @param owningTeam        Owning team label (e.g. "Core Team")
     * @return List of newly registered/updated tools
     */
    public List<RegisteredTool> importOpenApi(String openApiJsonString, String defaultBaseUrl, String owningTeam) throws Exception {
        JsonNode root = objectMapper.readTree(openApiJsonString);
        JsonNode pathsNode = root.path("paths");
        if (pathsNode.isMissingNode() || !pathsNode.isObject()) {
            throw new IllegalArgumentException("Invalid OpenAPI spec: 'paths' object is missing");
        }

        String baseUrl = defaultBaseUrl != null ? defaultBaseUrl : "";
        if (root.has("servers") && root.path("servers").isArray() && !root.path("servers").isEmpty()) {
            baseUrl = root.path("servers").get(0).path("url").asText("");
        }

        List<RegisteredTool> importedTools = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> pathsIterator = pathsNode.fields();

        while (pathsIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathsIterator.next();
            String path = pathEntry.getKey();
            JsonNode methodsNode = pathEntry.getValue();

            Iterator<Map.Entry<String, JsonNode>> methodsIterator = methodsNode.fields();
            while (methodsIterator.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodsIterator.next();
                String method = methodEntry.getKey().toUpperCase();
                if (!Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
                    continue;
                }

                JsonNode operationNode = methodEntry.getValue();
                String operationId = operationNode.path("operationId").asText();
                String toolName = sanitizeToolName(operationId, method, path);
                if (com.agentgate.gateway.registry.controller.ToolRegistryController.isReservedToolName(toolName)) {
                    throw new IllegalArgumentException("Cannot import tool with reserved name: " + toolName);
                }

                String description = operationNode.path("description").asText(
                        operationNode.path("summary").asText("Tool for " + method + " " + path)
                );

                RiskLevel riskLevel = determineRiskLevel(method, toolName, description);
                String team = owningTeam != null ? owningTeam : extractTeam(operationNode, root);

                String inputSchemaJson = extractInputSchema(operationNode);
                String fullEndpointUrl = baseUrl + path;

                RegisteredTool tool = new RegisteredTool(
                        UUID.randomUUID().toString(),
                        toolName,
                        description,
                        inputSchemaJson,
                        riskLevel,
                        team,
                        fullEndpointUrl,
                        ProtocolType.REST,
                        method,
                        true,
                        Instant.now(),
                        Instant.now()
                );

                repository.save(tool);
                importedTools.add(tool);
                log.info("Registered tool '{}' via OpenAPI import [endpoint={}, risk={}]", toolName, fullEndpointUrl, riskLevel);
            }
        }

        return importedTools;
    }

    private String sanitizeToolName(String operationId, String method, String path) {
        if (operationId != null && !operationId.isBlank()) {
            return operationId.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        }
        String cleanPath = path.replaceAll("[{}]", "").replaceAll("[^a-zA-Z0-9]", "_");
        return (method + "_" + cleanPath).replaceAll("_+", "_").toLowerCase();
    }

    private RiskLevel determineRiskLevel(String method, String toolName, String description) {
        String lowerDesc = (toolName + " " + description).toLowerCase();
        if (lowerDesc.contains("refund") || lowerDesc.contains("transfer") || lowerDesc.contains("delete") || lowerDesc.contains("cancel")) {
            return RiskLevel.HIGH;
        }
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private String extractTeam(JsonNode operationNode, JsonNode root) {
        if (operationNode.has("tags") && operationNode.path("tags").isArray() && !operationNode.path("tags").isEmpty()) {
            return operationNode.path("tags").get(0).asText() + " Team";
        }
        if (root.path("info").has("contact") && root.path("info").path("contact").has("name")) {
            return root.path("info").path("contact").path("name").asText();
        }
        return "Engineering Team";
    }

    private String extractInputSchema(JsonNode operationNode) {
        ObjectNode schemaObj = objectMapper.createObjectNode();
        schemaObj.put("type", "object");
        ObjectNode propertiesObj = schemaObj.putObject("properties");
        ArrayNode requiredArr = schemaObj.putArray("required");

        // Path and query parameters
        JsonNode parametersNode = operationNode.path("parameters");
        if (parametersNode.isArray()) {
            for (JsonNode param : parametersNode) {
                String name = param.path("name").asText();
                if (name.isBlank()) continue;

                boolean required = param.path("required").asBoolean(false);
                if (required) {
                    requiredArr.add(name);
                }

                ObjectNode prop = propertiesObj.putObject(name);
                String type = param.path("schema").path("type").asText("string");
                prop.put("type", type);
                if (param.has("description")) {
                    prop.put("description", param.path("description").asText());
                }
            }
        }

        // Request Body parameters
        JsonNode requestBodyNode = operationNode.path("requestBody");
        JsonNode contentNode = requestBodyNode.path("content").path("application/json").path("schema");
        if (!contentNode.isMissingNode() && contentNode.has("properties")) {
            JsonNode bodyProps = contentNode.path("properties");
            Iterator<Map.Entry<String, JsonNode>> bodyFields = bodyProps.fields();
            while (bodyFields.hasNext()) {
                Map.Entry<String, JsonNode> field = bodyFields.next();
                propertiesObj.set(field.getKey(), field.getValue());
            }
            if (contentNode.has("required") && contentNode.path("required").isArray()) {
                for (JsonNode req : contentNode.path("required")) {
                    requiredArr.add(req.asText());
                }
            }
        }

        return schemaObj.toString();
    }
}
