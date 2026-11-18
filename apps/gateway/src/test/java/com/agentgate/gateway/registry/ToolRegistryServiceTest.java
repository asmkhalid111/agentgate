package com.agentgate.gateway.registry;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.pipeline.*;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.registry.service.OpenApiImporterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ToolRegistryServiceTest {

    @Autowired
    private RegisteredToolRepository repository;

    @Autowired
    private OpenApiImporterService importerService;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private StructuredPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        // Clear and seed test tool
        repository.deleteById("test-refund-tool");
    }

    @Test
    @DisplayName("Should successfully register and query tools by name and agent permission")
    void shouldRegisterAndQueryTool() {
        RegisteredTool tool = new RegisteredTool(
                "test-refund-tool",
                "test_orders_refund",
                "Refund tool for unit testing",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Finance Team",
                "http://localhost:8082/api/orders/{orderId}/refund",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );

        repository.save(tool);
        repository.addPermission("test_orders_refund", "agent-finance-bot");

        Optional<RegisteredTool> found = repository.findByName("test_orders_refund");
        assertTrue(found.isPresent());
        assertEquals("test_orders_refund", found.get().name());
        assertEquals(RiskLevel.HIGH, found.get().riskLevel());

        assertTrue(repository.isAgentPermitted("test_orders_refund", "agent-finance-bot"));
        assertFalse(repository.isAgentPermitted("test_orders_refund", "agent-support-bot"));

        List<RegisteredTool> permittedTools = repository.findPermittedToolsForAgent("agent-finance-bot");
        assertTrue(permittedTools.stream().anyMatch(t -> t.name().equals("test_orders_refund")));
    }

    @Test
    @DisplayName("OpenAPI importer should parse OpenAPI JSON and register tools with schema")
    void openApiImporter_shouldParseAndRegisterTools() throws Exception {
        String openApiJson = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "Customer Orders API", "version": "1.0.0" },
                  "paths": {
                    "/api/customers/{customerId}/orders": {
                      "get": {
                        "operationId": "get_customer_orders",
                        "summary": "List orders for a customer",
                        "parameters": [
                          { "name": "customerId", "in": "path", "required": true, "schema": { "type": "string" } }
                        ],
                        "responses": { "200": { "description": "OK" } }
                      }
                    }
                  }
                }
                """;

        List<RegisteredTool> imported = importerService.importOpenApi(openApiJson, "http://localhost:8082", "Customer Team");
        assertFalse(imported.isEmpty());

        RegisteredTool tool = imported.get(0);
        assertEquals("get_customer_orders", tool.name());
        assertEquals(RiskLevel.LOW, tool.riskLevel());
        assertEquals(ProtocolType.REST, tool.protocolType());
        assertTrue(tool.inputSchema().contains("customerId"));
    }

    @Test
    @DisplayName("ToolExecutionPipeline should block unauthorized agent directly invoking tool")
    void pipeline_shouldBlockUnauthorizedAgentDirectInvocation() {
        RegisteredTool tool = new RegisteredTool(
                "tool-auth-test",
                "secure_vault_access",
                "Secure vault",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Sec Team",
                "http://localhost:8082/api/vault",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        repository.save(tool);

        // agent-unauthorized-bot has NOT been granted permission
        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-unauthorized-bot",
                "secure_vault_access",
                Map.of(),
                null,
                RiskLevel.HIGH
        );

        assertThrows(SecurityException.class, () -> pipeline.execute(ctx));
    }

    @Test
    @DisplayName("ToolExecutionPipeline should block invocation if tool is disabled")
    void pipeline_shouldBlockDisabledTool() {
        RegisteredTool disabledTool = new RegisteredTool(
                "tool-disabled-test",
                "disabled_system_tool",
                "Disabled tool",
                "{\"type\":\"object\"}",
                RiskLevel.LOW,
                "Ops",
                "http://localhost:8082/api/disabled",
                ProtocolType.REST,
                "GET",
                false, // disabled
                Instant.now(),
                Instant.now()
        );
        repository.save(disabledTool);
        repository.addPermission("disabled_system_tool", "agent-finance-bot");

        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-finance-bot",
                "disabled_system_tool",
                Map.of(),
                null,
                RiskLevel.LOW
        );

        assertThrows(IllegalStateException.class, () -> pipeline.execute(ctx));
    }

    @Test
    @DisplayName("ToolExecutionPipeline should execute downstream MCP protocol tools")
    void pipeline_shouldSupportMcpProtocolTools() {
        RegisteredTool mcpTool = new RegisteredTool(
                "tool-mcp-test",
                "downstream_mcp_echo",
                "Downstream MCP service",
                "{\"type\":\"object\"}",
                RiskLevel.LOW,
                "AI Ops",
                "http://localhost:9090/mcp",
                ProtocolType.MCP,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        repository.save(mcpTool);
        repository.addPermission("downstream_mcp_echo", "agent-finance-bot");

        policyRepository.save(new PolicyRule(
                "pol-mcp-test",
                "Allow MCP Echo",
                "agent-finance-*",
                "downstream_mcp_echo",
                List.of(),
                PolicyAction.ALLOW,
                false,
                1,
                1,
                true
        ));
        evaluator.reloadPolicies();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-finance-bot",
                "downstream_mcp_echo",
                Map.of("message", "hello-mcp"),
                null,
                RiskLevel.LOW
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertTrue(result.success());
        assertNotNull(result.data());
    }
}
