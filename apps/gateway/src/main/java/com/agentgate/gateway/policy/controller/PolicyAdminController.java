package com.agentgate.gateway.policy.controller;

import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.domain.StructuredCondition;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyAdminController {

    private final PolicyRepository repository;
    private final StructuredPolicyEvaluator evaluator;

    public PolicyAdminController(PolicyRepository repository, StructuredPolicyEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    @GetMapping
    public List<PolicyRule> listPolicies(
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int size) {
        return repository.findAllIncludeDisabled().stream()
                .sorted(java.util.Comparator.comparing(PolicyRule::id))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyRule> getPolicy(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public ResponseEntity<PolicyRule> createOrUpdatePolicy(@jakarta.validation.Valid @RequestBody PolicyRule rule) {
        String ruleId = (rule.id() != null && !rule.id().isBlank()) ? rule.id() : UUID.randomUUID().toString();
        PolicyRule ruleToSave = new PolicyRule(
                ruleId,
                rule.name(),
                rule.agentPattern(),
                rule.toolPattern(),
                rule.conditions() != null ? rule.conditions() : List.of(),
                rule.action() != null ? rule.action() : PolicyAction.DENY,
                rule.dryRun(),
                rule.priority() > 0 ? rule.priority() : 100,
                rule.version() > 0 ? rule.version() : 1,
                rule.enabled()
        );

        repository.save(ruleToSave);
        evaluator.reloadPolicies(); // Trigger immediate in-memory cache update (FR3 <=10s)
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleToSave);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable String id) {
        repository.deleteById(id);
        evaluator.reloadPolicies();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> triggerReload() {
        evaluator.reloadPolicies();
        return ResponseEntity.ok(Map.of("status", "RELOADED"));
    }
}
