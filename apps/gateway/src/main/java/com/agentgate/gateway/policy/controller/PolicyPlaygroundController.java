package com.agentgate.gateway.policy.controller;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.RiskLevel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/policies/playground")
public class PolicyPlaygroundController {

    private final StructuredPolicyEvaluator evaluator;

    public PolicyPlaygroundController(StructuredPolicyEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public record PlaygroundRequest(
            String agentId,
            String toolName,
            Map<String, Object> arguments,
            String userId,
            Map<String, Object> userContext,
            String riskLevel,
            PolicyRule draftRule
    ) {}

    public record PlaygroundResponse(
            String decision,
            String matchedRuleId,
            String matchedRuleName,
            String reason,
            boolean dryRun,
            long evaluationTimeMs
    ) {}

    @PostMapping
    public ResponseEntity<PlaygroundResponse> testPolicyCall(@RequestBody PlaygroundRequest request) {
        RiskLevel risk = RiskLevel.LOW;
        if (request.riskLevel() != null && !request.riskLevel().isBlank()) {
            try {
                risk = RiskLevel.valueOf(request.riskLevel().toUpperCase());
            } catch (Exception ignored) {}
        }

        String resolvedUserId = request.userId();
        if (resolvedUserId == null && request.userContext() != null) {
            Object uid = request.userContext().get("userId");
            if (uid != null) {
                resolvedUserId = uid.toString();
            }
        }

        ToolExecutionContext ctx = new ToolExecutionContext(
                request.agentId() != null ? request.agentId() : "anonymous",
                request.toolName() != null ? request.toolName() : "unknown_tool",
                request.arguments() != null ? request.arguments() : Collections.emptyMap(),
                resolvedUserId,
                risk
        );

        PolicyDecision decision;
        if (request.draftRule() != null) {
            // Evaluate against draft policy directly (FR9 draft preview)
            decision = evaluator.evaluateDraft(request.draftRule(), ctx);
        } else {
            // Evaluate against live cached active policies
            decision = evaluator.evaluate(ctx);
        }

        PlaygroundResponse response = new PlaygroundResponse(
                decision.action().name(),
                decision.matchedRuleId(),
                decision.matchedRuleName(),
                decision.reason(),
                decision.dryRun(),
                decision.evaluationTimeMs()
        );

        return ResponseEntity.ok(response);
    }
}
