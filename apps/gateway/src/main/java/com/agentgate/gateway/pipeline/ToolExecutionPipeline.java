package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ValidationResult;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);
    private final RegisteredToolRepository toolRepository;
    private final List<ToolAdapter> adapters;
    private final StructuredPolicyEvaluator policyEvaluator;
    private final JsonSchemaValidationGuard validationGuard;
    private final PiiRedactionGuard piiRedactionGuard;

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard) {
        this.toolRepository = toolRepository;
        this.adapters = adapters;
        this.policyEvaluator = policyEvaluator;
        this.validationGuard = validationGuard;
        this.piiRedactionGuard = piiRedactionGuard;
    }

    /**
     * Executes a tool through the shared governance pipeline.
     * Enforces tool status, direct agent authorization, JSON schema validation, policy rules,
     * and clearance-aware output PII redaction.
     */
    public ToolExecutionResult execute(ToolExecutionContext context) {
        RegisteredTool tool = toolRepository.findByName(context.toolName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + context.toolName()));

        if (!tool.enabled()) {
            log.warn("Blocked call to disabled tool '{}' from agent '{}'", context.toolName(), context.agentId());
            throw new IllegalStateException("Tool '" + context.toolName() + "' is currently disabled");
        }

        // Direct invocation authorization check (Reviewer Amendment 4)
        if (!toolRepository.isAgentPermitted(context.toolName(), context.agentId())) {
            log.warn("Security violation: Agent '{}' unauthorized for tool '{}'", context.agentId(), context.toolName());
            throw new SecurityException("Agent '" + context.agentId() + "' is not authorized to invoke tool '" + context.toolName() + "'");
        }

        // JSON Schema Input Validation (FR5)
        ValidationResult validation = validationGuard.validate(tool, context.arguments());
        if (!validation.valid()) {
            log.warn("Input validation failed for tool '{}' from agent '{}': {}", context.toolName(), context.agentId(), validation.error());
            return ToolExecutionResult.failure(validation.error(), 0L);
        }

        ToolExecutionContext enrichedContext = context.withRiskLevel(tool.riskLevel());

        // Policy Evaluation (FR3, QA4)
        PolicyDecision decision = policyEvaluator.evaluate(enrichedContext);
        if (decision.action() == PolicyAction.DENY) {
            log.warn("Call blocked by policy: rule='{}', reason='{}'", decision.matchedRuleName(), decision.reason());
            return ToolExecutionResult.failure("POLICY_DENIED: " + decision.reason(), decision.evaluationTimeMs());
        }

        if (decision.action() == PolicyAction.REQUIRE_APPROVAL) {
            log.info("Call requires human approval: rule='{}'", decision.matchedRuleName());
            return ToolExecutionResult.failure("REQUIRE_APPROVAL: Call held for human approval [Rule: " + decision.matchedRuleName() + "]", decision.evaluationTimeMs());
        }

        ToolAdapter adapter = adapters.stream()
                .filter(a -> a.supports(tool.protocolType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported protocol: " + tool.protocolType()));

        ToolExecutionResult rawResult = adapter.execute(tool, enrichedContext);

        // Error safety: sanitize agent-visible error messages if execution failed
        if (!rawResult.success()) {
            if (rawResult.error() != null && !enrichedContext.hasHighClearance()) {
                String sanitizedError = piiRedactionGuard.redactString(rawResult.error());
                return ToolExecutionResult.failure(sanitizedError, rawResult.latencyMs());
            }
            return rawResult;
        }

        // Clearance-aware output PII redaction (FR5)
        Object redactedData = piiRedactionGuard.redact(rawResult.data(), enrichedContext);
        return ToolExecutionResult.success(redactedData, rawResult.latencyMs());
    }
}
