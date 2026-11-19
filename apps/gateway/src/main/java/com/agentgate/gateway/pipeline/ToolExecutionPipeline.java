package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.guard.ValidationResult;
import com.agentgate.gateway.killswitch.dto.KillSwitchCheckResult;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.ratelimit.RateLimitDecision;
import com.agentgate.gateway.ratelimit.RateLimiter;
import com.agentgate.gateway.ratelimit.WriteCapDecision;
import com.agentgate.gateway.ratelimit.WriteCapLimiter;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);
    private final RegisteredToolRepository toolRepository;
    private final List<ToolAdapter> adapters;
    private final StructuredPolicyEvaluator policyEvaluator;
    private final JsonSchemaValidationGuard validationGuard;
    private final PiiRedactionGuard piiRedactionGuard;
    private final ResponseTruncationGuard responseTruncationGuard;
    private final ApprovalService approvalService;
    private final RateLimiter rateLimiter;
    private final WriteCapLimiter writeCapLimiter;
    private final KillSwitchService killSwitchService;
    private final Duration approvalTimeout;

    @Autowired
    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService,
            RateLimiter rateLimiter,
            WriteCapLimiter writeCapLimiter,
            @Autowired(required = false) KillSwitchService killSwitchService,
            @Value("${agentgate.approval.default-timeout-seconds:900}") long approvalTimeoutSeconds) {
        this.toolRepository = toolRepository;
        this.adapters = adapters;
        this.policyEvaluator = policyEvaluator;
        this.validationGuard = validationGuard;
        this.piiRedactionGuard = piiRedactionGuard;
        this.responseTruncationGuard = responseTruncationGuard;
        this.approvalService = approvalService;
        this.rateLimiter = rateLimiter;
        this.writeCapLimiter = writeCapLimiter;
        this.killSwitchService = killSwitchService;
        this.approvalTimeout = Duration.ofSeconds(approvalTimeoutSeconds > 0 ? approvalTimeoutSeconds : 900);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService,
            RateLimiter rateLimiter,
            WriteCapLimiter writeCapLimiter,
            KillSwitchService killSwitchService) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, 900L);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService,
            RateLimiter rateLimiter,
            WriteCapLimiter writeCapLimiter,
            long approvalTimeoutSeconds) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, null, approvalTimeoutSeconds);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService,
            RateLimiter rateLimiter,
            long approvalTimeoutSeconds) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, null, approvalTimeoutSeconds);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService,
            long approvalTimeoutSeconds) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, null, null, approvalTimeoutSeconds);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, null, 900L);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, null, null, 900L);
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

        // Emergency Kill Switch Check at Ingress (FR8)
        // Blocks requests immediately before rate limits, validation, or approval persistence
        if (killSwitchService != null) {
            KillSwitchCheckResult killDecision = killSwitchService.checkBlocked(
                    context.agentId(),
                    context.toolName(),
                    tool.isWriteOperation()
            );
            if (killDecision.blocked()) {
                log.warn("Execution blocked by emergency kill switch at ingress for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), killDecision.message());
                return ToolExecutionResult.failure(killDecision.message(), 0L);
            }
        }

        // Atomic Ingress Rate Limiting (FR6)
        // Evaluated after authentication/permission check, before policy evaluation, schema validation, and approval persistence
        if (rateLimiter != null) {
            RateLimitDecision rateDecision = rateLimiter.tryAcquire(context.agentId(), context.toolName());
            if (!rateDecision.allowed()) {
                log.warn("Rate limit check failed for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), rateDecision.message());
                return ToolExecutionResult.failure(rateDecision.message(), 0L);
            }
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
            String ruleId = decision.matchedRuleId();
            if (ruleId == null || ruleId.isBlank()) {
                log.error("Policy action REQUIRE_APPROVAL for tool '{}' missing rule identifier", context.toolName());
                return ToolExecutionResult.failure(
                        "REQUIRE_APPROVAL_INVALID_POLICY: Missing policy rule identifier; tool was not executed.",
                        decision.evaluationTimeMs()
                );
            }

            if (approvalService == null) {
                log.error("ApprovalService unavailable for tool '{}' requiring approval", context.toolName());
                return ToolExecutionResult.failure(
                        "REQUIRE_APPROVAL_PERSISTENCE_FAILED: Approval request could not be recorded; tool was not executed.",
                        decision.evaluationTimeMs()
                );
            }

            try {
                ApprovalRequest pendingReq = approvalService.createRequest(
                        context.agentId(),
                        context.userId(),
                        context.toolName(),
                        ruleId,
                        context.arguments(),
                        approvalTimeout
                );
                String ruleName = decision.matchedRuleName() != null && !decision.matchedRuleName().isBlank()
                        ? decision.matchedRuleName()
                        : ruleId;
                return ToolExecutionResult.failure(
                        "REQUIRE_APPROVAL: Call held for human approval [ApprovalId: " + pendingReq.id() + ", Rule: " + ruleName + "]",
                        decision.evaluationTimeMs()
                );
            } catch (Exception e) {
                log.error("Failed to persist approval request for tool '{}' by agent '{}' [rule='{}']: {}",
                        context.toolName(), context.agentId(), ruleId, e.getClass().getSimpleName());
                return ToolExecutionResult.failure(
                        "REQUIRE_APPROVAL_PERSISTENCE_FAILED: Approval request could not be recorded; tool was not executed.",
                        decision.evaluationTimeMs()
                );
            }
        }

        // Hourly Write Cap check for mutating tools (FR6)
        if (tool.isWriteOperation() && writeCapLimiter != null) {
            WriteCapDecision writeDecision = writeCapLimiter.tryAcquireWrite(context.agentId());
            if (!writeDecision.allowed()) {
                log.warn("Write cap check failed for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), writeDecision.message());
                return ToolExecutionResult.failure(writeDecision.message(), decision.evaluationTimeMs());
            }
        }

        ToolAdapter adapter = adapters.stream()
                .filter(a -> a.supports(tool.protocolType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported protocol: " + tool.protocolType()));

        // Emergency Kill Switch Check immediately pre-dispatch (FR8)
        // Defends against race conditions where kill switch was activated after ingress check
        if (killSwitchService != null) {
            KillSwitchCheckResult preDispatchDecision = killSwitchService.checkBlocked(
                    context.agentId(),
                    context.toolName(),
                    tool.isWriteOperation()
            );
            if (preDispatchDecision.blocked()) {
                log.warn("Direct execution blocked by emergency kill switch immediately pre-dispatch for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), preDispatchDecision.message());
                return ToolExecutionResult.failure(preDispatchDecision.message(), decision.evaluationTimeMs());
            }
        }

        ToolExecutionResult rawResult = adapter.execute(tool, enrichedContext);

        // Error safety: sanitize agent-visible error messages if execution failed
        if (!rawResult.success()) {
            if (rawResult.error() != null) {
                String sanitizedError = enrichedContext.hasHighClearance()
                        ? rawResult.error()
                        : piiRedactionGuard.redactString(rawResult.error());
                Object truncatedError = responseTruncationGuard.truncate(sanitizedError);
                return ToolExecutionResult.failure(String.valueOf(truncatedError), rawResult.latencyMs());
            }
            return rawResult;
        }

        // Clearance-aware output PII redaction (FR5)
        Object redactedData = piiRedactionGuard.redact(rawResult.data(), enrichedContext);

        // Oversized tool response truncation (FR5)
        Object truncatedData = responseTruncationGuard.truncate(redactedData);
        return ToolExecutionResult.success(truncatedData, rawResult.latencyMs());
    }
}
