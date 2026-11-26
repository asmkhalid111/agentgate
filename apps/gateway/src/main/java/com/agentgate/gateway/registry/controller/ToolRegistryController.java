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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/registry/tools")
public class ToolRegistryController {

    public static final java.util.Set<String> RESERVED_TOOL_NAMES = java.util.Set.of(
            "get_approval_result",
            "m01_status",
            "m01_restricted_admin_tool"
    );

    public static boolean isReservedToolName(String name) {
        if (name == null) return false;
        return RESERVED_TOOL_NAMES.contains(name.trim().toLowerCase());
    }

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
    public List<RegisteredTool> listTools(
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int size) {
        return repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(RegisteredTool::name))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<RegisteredTool> getTool(@PathVariable String name) {
        return repository.findByName(name)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record RegisterToolRequest(@NotBlank String name, String description, String inputSchema,
            String riskLevel, String owningTeam, String endpointUrl, String protocolType, String httpMethod,
            Boolean enabled) {}

    public record OpenApiImportRequest(@NotBlank String spec, String baseUrl, String owningTeam) {}

    public record GrantPermissionRequest(@NotBlank String agentId) {}

    @PostMapping
    public ResponseEntity<RegisteredTool> registerTool(@Valid @RequestBody RegisterToolRequest request) {
        String name = request.name();
        if (isReservedToolName(name)) {
            throw new IllegalArgumentException("Tool name '" + name + "' is reserved for built-in gateway operations");
        }
        String description = java.util.Objects.requireNonNullElse(request.description(), "");
        String inputSchema = java.util.Objects.requireNonNullElse(request.inputSchema(), "{\"type\":\"object\"}");
        String riskStr = java.util.Objects.requireNonNullElse(request.riskLevel(), "LOW");
        String owningTeam = java.util.Objects.requireNonNullElse(request.owningTeam(), "Default Team");
        String endpointUrl = request.endpointUrl();
        String protocolStr = java.util.Objects.requireNonNullElse(request.protocolType(), "REST");
        String httpMethod = java.util.Objects.requireNonNullElse(request.httpMethod(), "POST");
        boolean enabled = !Boolean.FALSE.equals(request.enabled());

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
    public ResponseEntity<List<RegisteredTool>> importOpenApi(@Valid @RequestBody OpenApiImportRequest request) throws Exception {
        String spec = request.spec();
        String baseUrl = java.util.Objects.requireNonNullElse(request.baseUrl(), "");
        String owningTeam = java.util.Objects.requireNonNullElse(request.owningTeam(), "Imported API");

        List<RegisteredTool> imported = importerService.importOpenApi(spec, baseUrl, owningTeam);
        for (RegisteredTool tool : imported) {
            eventPublisher.publishEvent(new ToolRegisteredEvent(tool));
        }
        return ResponseEntity.ok(imported);
    }

    @GetMapping("/{toolName}/permissions")
    public List<String> getPermissions(@PathVariable String toolName,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int size) {
        return repository.getPermittedAgents(toolName).stream()
                .sorted()
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @PostMapping("/{toolName}/permissions")
    public ResponseEntity<Void> grantPermission(@PathVariable String toolName, @Valid @RequestBody GrantPermissionRequest request) {
        repository.addPermission(toolName, request.agentId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{toolName}/permissions/{agentId}")
    public ResponseEntity<Void> revokePermission(@PathVariable String toolName, @PathVariable String agentId) {
        repository.removePermission(toolName, agentId);
        return ResponseEntity.noContent().build();
    }
}
