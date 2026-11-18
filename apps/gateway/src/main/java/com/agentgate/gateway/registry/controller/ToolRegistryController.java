package com.agentgate.gateway.registry.controller;

import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.event.ToolDeletedEvent;
import com.agentgate.gateway.registry.event.ToolRegisteredEvent;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.agentgate.gateway.registry.service.OpenApiImporterService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/registry/tools")
public class ToolRegistryController {

    private final RegisteredToolRepository repository;
    private final OpenApiImporterService importerService;
    private final ApplicationEventPublisher eventPublisher;

    public ToolRegistryController(
            RegisteredToolRepository repository,
            OpenApiImporterService importerService,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.importerService = importerService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping
    public List<RegisteredTool> listTools() {
        return repository.findAll();
    }

    @GetMapping("/{name}")
    public ResponseEntity<RegisteredTool> getTool(@PathVariable String name) {
        return repository.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RegisteredTool> registerTool(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.getOrDefault("description", "");
        String inputSchema = (String) request.getOrDefault("inputSchema", "{\"type\":\"object\"}");
        String riskStr = (String) request.getOrDefault("riskLevel", "LOW");
        String owningTeam = (String) request.getOrDefault("owningTeam", "Default Team");
        String endpointUrl = (String) request.get("endpointUrl");
        String protocolStr = (String) request.getOrDefault("protocolType", "REST");
        String httpMethod = (String) request.getOrDefault("httpMethod", "POST");
        boolean enabled = (boolean) request.getOrDefault("enabled", true);

        RegisteredTool tool = new RegisteredTool(
                UUID.randomUUID().toString(),
                name,
                description,
                inputSchema,
                RiskLevel.valueOf(riskStr.toUpperCase()),
                owningTeam,
                endpointUrl,
                ProtocolType.valueOf(protocolStr.toUpperCase()),
                httpMethod.toUpperCase(),
                enabled,
                Instant.now(),
                Instant.now()
        );

        repository.save(tool);
        eventPublisher.publishEvent(new ToolRegisteredEvent(tool));
        return ResponseEntity.status(HttpStatus.CREATED).body(tool);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTool(@PathVariable String id) {
        repository.findById(id).ifPresent(tool -> {
            repository.deleteById(id);
            eventPublisher.publishEvent(new ToolDeletedEvent(tool.name()));
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import-openapi")
    public ResponseEntity<List<RegisteredTool>> importOpenApi(@RequestBody Map<String, String> request) throws Exception {
        String spec = request.get("spec");
        String baseUrl = request.getOrDefault("baseUrl", "");
        String owningTeam = request.getOrDefault("owningTeam", "Imported API");

        if (spec == null || spec.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        List<RegisteredTool> imported = importerService.importOpenApi(spec, baseUrl, owningTeam);
        for (RegisteredTool tool : imported) {
            eventPublisher.publishEvent(new ToolRegisteredEvent(tool));
        }
        return ResponseEntity.ok(imported);
    }

    @GetMapping("/{toolName}/permissions")
    public List<String> getPermissions(@PathVariable String toolName) {
        return repository.getPermittedAgents(toolName);
    }

    @PostMapping("/{toolName}/permissions")
    public ResponseEntity<Void> grantPermission(@PathVariable String toolName, @RequestBody Map<String, String> request) {
        String agentId = request.get("agentId");
        if (agentId != null && !agentId.isBlank()) {
            repository.addPermission(toolName, agentId);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.badRequest().build();
    }

    @DeleteMapping("/{toolName}/permissions/{agentId}")
    public ResponseEntity<Void> revokePermission(@PathVariable String toolName, @PathVariable String agentId) {
        repository.removePermission(toolName, agentId);
        return ResponseEntity.noContent().build();
    }
}
