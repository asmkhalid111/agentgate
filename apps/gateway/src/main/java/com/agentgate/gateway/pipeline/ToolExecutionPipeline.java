package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.service.AuditRecorder;
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
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreaker;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreakerRegistry;
import com.agentgate.gateway.resilience.circuitbreaker.ToolFailureClassifier;
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
    private final AuditRecorder auditRecorder;
    private final ToolCircuitBreakerRegistry circuitBreakerRegistry;
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
            @Autowired(required = false) AuditRecorder auditRecorder,
            @Autowired(required = false) ToolCircuitBreakerRegistry circuitBreakerRegistry,
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
        this.auditRecorder = auditRecorder;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
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
            KillSwitchService killSwitchService,
            AuditRecorder auditRecorder,
            ToolCircuitBreakerRegistry circuitBreakerRegistry) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, auditRecorder, circuitBreakerRegistry, 900L);
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
            KillSwitchService killSwitchService,
            AuditRecorder auditRecorder,
            long approvalTimeoutSeconds) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, auditRecorder, null, approvalTimeoutSeconds);
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
            KillSwitchService killSwitchService,
            AuditRecorder auditRecorder) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, auditRecorder, null, 900L);
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
            KillSwitchService killSwitchService,
            long approvalTimeoutSeconds) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, null, null, approvalTimeoutSeconds);
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
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, killSwitchService, null, null, 900L);
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
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, writeCapLimiter, null, null, null, approvalTimeoutSeconds);
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
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, rateLimiter, null, null, null, null, approvalTimeoutSeconds);
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
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, null, null, null, null, null, approvalTimeoutSeconds);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ApprovalService approvalService) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, approvalService, null, null, null, null, null, 900L);
    }

    public ToolExecutionPipeline(
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard) {
        this(toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, null, null, null, null, null, null, 900L);
    }

    /**
     * Executes a tool through the shared governance pipeline.
     * Enforces tool status, direct agent authorization, JSON schema validation, policy rules,
     * circuit breaker state, write quotas, and clearance-aware output PII redaction.
     */
    public ToolExecutionResult execute(ToolExecutionContext context) {
        RegisteredTool tool = toolRepository.findByName(context.toolName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + context.toolName()));

        if (!tool.enabled()) {
            log.warn("Blocked call to disabled tool '{}' from agent '{}'", context.toolName(), context.agentId());
            if (auditRecorder != null) {
                auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "TOOL_DISABLED", null, null, "Tool '" + context.toolName() + "' is currently disabled", 0L);
            }
            throw new IllegalStateException("Tool '" + context.toolName() + "' is currently disabled");
        }

        // Direct invocation authorization check (Reviewer Amendment 4)
        if (!toolRepository.isAgentPermitted(context.toolName(), context.agentId())) {
            log.warn("Security violation: Agent '{}' unauthorized for tool '{}'", context.agentId(), context.toolName());
            if (auditRecorder != null) {
                auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "AGENT_UNAUTHORIZED", null, null, "Agent '" + context.agentId() + "' is not authorized to invoke tool '" + context.toolName() + "'", 0L);
            }
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
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "KILL_SWITCH_BLOCKED", null, null, killDecision.message(), 0L);
                }
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
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "RATE_LIMIT_EXCEEDED", null, null, rateDecision.message(), 0L);
                }
                return ToolExecutionResult.failure(rateDecision.message(), 0L);
            }
        }

        // JSON Schema Input Validation (FR5)
        ValidationResult validation = validationGuard.validate(tool, context.arguments());
        if (!validation.valid()) {
            log.warn("Input validation failed for tool '{}' from agent '{}': {}", context.toolName(), context.agentId(), validation.error());
            if (auditRecorder != null) {
                auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "SCHEMA_VALIDATION_FAILED", null, null, validation.error(), 0L);
            }
            return ToolExecutionResult.failure(validation.error(), 0L);
        }

        ToolExecutionContext enrichedContext = context.withRiskLevel(tool.riskLevel());

        // Policy Evaluation (FR3, QA4)
        PolicyDecision decision = policyEvaluator.evaluate(enrichedContext);
        if (decision.action() == PolicyAction.DENY) {
            log.warn("Call blocked by policy: rule='{}', reason='{}'", decision.matchedRuleName(), decision.reason());
            if (auditRecorder != null) {
                auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "POLICY_DENIED", decision.matchedRuleId(), decision.matchedRuleName(), decision.reason(), decision.evaluationTimeMs());
            }
            return ToolExecutionResult.failure("POLICY_DENIED: " + decision.reason(), decision.evaluationTimeMs());
        }

        if (decision.action() == PolicyAction.REQUIRE_APPROVAL) {
            String ruleId = decision.matchedRuleId();
            if (ruleId == null || ruleId.isBlank()) {
                log.error("Policy action REQUIRE_APPROVAL for tool '{}' missing rule identifier", context.toolName());
                String msg = "REQUIRE_APPROVAL_INVALID_POLICY: Missing policy rule identifier; tool was not executed.";
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "REQUIRE_APPROVAL_INVALID_POLICY", null, null, msg, decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(msg, decision.evaluationTimeMs());
            }

            if (approvalService == null) {
                log.error("ApprovalService unavailable for tool '{}' requiring approval", context.toolName());
                String msg = "REQUIRE_APPROVAL_PERSISTENCE_FAILED: Approval request could not be recorded; tool was not executed.";
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "REQUIRE_APPROVAL_PERSISTENCE_FAILED", ruleId, decision.matchedRuleName(), msg, decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(msg, decision.evaluationTimeMs());
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
            } catch (IllegalStateException e) {
                log.warn("Approval creation blocked by emergency kill switch for tool '{}' by agent '{}': {}",
                        context.toolName(), context.agentId(), e.getMessage());
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "KILL_SWITCH_BLOCKED", ruleId, decision.matchedRuleName(), e.getMessage(), decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(e.getMessage(), decision.evaluationTimeMs());
            } catch (Exception e) {
                log.error("Failed to persist approval request for tool '{}' by agent '{}' [rule='{}']: {}",
                        context.toolName(), context.agentId(), ruleId, e.getClass().getSimpleName());
                String msg = "REQUIRE_APPROVAL_PERSISTENCE_FAILED: Approval request could not be recorded; tool was not executed.";
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "REQUIRE_APPROVAL_PERSISTENCE_FAILED", ruleId, decision.matchedRuleName(), msg, decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(msg, decision.evaluationTimeMs());
            }
        }

        // Circuit Breaker check (FR10) - fast rejection before write cap and pre-dispatch write intent
        ToolCircuitBreaker circuitBreaker = circuitBreakerRegistry != null ? circuitBreakerRegistry.getBreaker(tool.name()) : null;
        if (circuitBreaker != null && !circuitBreaker.tryAcquirePermission()) {
            log.warn("Direct execution fast-rejected by circuit breaker for tool '{}' [agent='{}']",
                    context.toolName(), context.agentId());
            String errorMsg = "TOOL_TEMPORARILY_UNAVAILABLE: Downstream tool is temporarily unavailable due to repeated failures.";
            if (auditRecorder != null) {
                auditRecorder.recordGovernanceDenial(
                        context.agentId(),
                        context.userId(),
                        context.toolName(),
                        context.arguments(),
                        "CIRCUIT_BREAKER_OPEN",
                        decision.matchedRuleId(),
                        decision.matchedRuleName(),
                        errorMsg,
                        decision.evaluationTimeMs()
                );
            }
            return ToolExecutionResult.failure(errorMsg, decision.evaluationTimeMs());
        }

        // Hourly Write Cap check for mutating tools (FR6)
        if (tool.isWriteOperation() && writeCapLimiter != null) {
            WriteCapDecision writeDecision = writeCapLimiter.tryAcquireWrite(context.agentId());
            if (!writeDecision.allowed()) {
                if (circuitBreaker != null) {
                    circuitBreaker.releasePermission();
                }
                log.warn("Write cap check failed for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), writeDecision.message());
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "WRITE_CAP_EXCEEDED", decision.matchedRuleId(), decision.matchedRuleName(), writeDecision.message(), decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(writeDecision.message(), decision.evaluationTimeMs());
            }
        }

        ToolAdapter adapter = adapters.stream()
                .filter(a -> a.supports(tool.protocolType()))
                .findFirst()
                .orElse(null);

        if (adapter == null) {
            if (circuitBreaker != null) {
                circuitBreaker.releasePermission();
            }
            throw new IllegalStateException("Unsupported protocol: " + tool.protocolType());
        }

        // Emergency Kill Switch Check immediately pre-dispatch (FR8)
        // Defends against race conditions where kill switch was activated after ingress check
        if (killSwitchService != null) {
            KillSwitchCheckResult preDispatchDecision = killSwitchService.checkBlocked(
                    context.agentId(),
                    context.toolName(),
                    tool.isWriteOperation()
            );
            if (preDispatchDecision.blocked()) {
                if (circuitBreaker != null) {
                    circuitBreaker.releasePermission();
                }
                log.warn("Direct execution blocked by emergency kill switch immediately pre-dispatch for agent '{}' on tool '{}': {}",
                        context.agentId(), context.toolName(), preDispatchDecision.message());
                if (auditRecorder != null) {
                    auditRecorder.recordGovernanceDenial(context.agentId(), context.userId(), context.toolName(), context.arguments(), "KILL_SWITCH_BLOCKED", decision.matchedRuleId(), decision.matchedRuleName(), preDispatchDecision.message(), decision.evaluationTimeMs());
                }
                return ToolExecutionResult.failure(preDispatchDecision.message(), decision.evaluationTimeMs());
            }
        }

        // Mandatory Pre-Dispatch Write Intent for write operations (FR7)
        if (tool.isWriteOperation() && auditRecorder != null) {
            try {
                auditRecorder.recordWriteDispatchIntent(
                        context.agentId(),
                        context.userId(),
                        context.toolName(),
                        context.arguments(),
                        decision.matchedRuleId(),
                        decision.matchedRuleName(),
                        null
                );
            } catch (Exception e) {
                if (circuitBreaker != null) {
                    circuitBreaker.releasePermission();
                }
                log.error("Failed to commit pre-dispatch write intent audit event for tool '{}' by agent '{}': {}",
                        context.toolName(), context.agentId(), e.getMessage());
                return ToolExecutionResult.failure(
                        "AUDIT_WRITE_INTENT_PERSISTENCE_FAILED: Pre-dispatch audit logging failed; tool was not executed.",
                        decision.evaluationTimeMs()
                );
            }
        }

        ToolExecutionResult rawResult;
        try {
            rawResult = adapter.execute(tool, enrichedContext);
        } catch (Throwable t) {
            log.error("Downstream tool adapter exception for tool '{}': {}", tool.name(), t.getMessage());
            if (circuitBreaker != null) {
                if (ToolFailureClassifier.isDownstreamFailure(t, null, t.getMessage())) {
                    circuitBreaker.recordFailure();
                } else {
                    circuitBreaker.releasePermission();
                }
            }
            boolean indeterminate = isIndeterminate(t, t.getMessage());
            String outcome = indeterminate ? "INDETERMINATE" : "FAILED";
            if (auditRecorder != null) {
                auditRecorder.recordDirectToolExecution(context.agentId(), context.userId(), tool.name(), context.arguments(), decision.matchedRuleId(), decision.matchedRuleName(), decision.action().name(), 0L, outcome, t.getMessage());
            }
            return ToolExecutionResult.failure("DOWNSTREAM_EXECUTION_ERROR: " + t.getMessage(), 0L);
        }

        // Downstream tool failure handling
        if (!rawResult.success()) {
            String error = rawResult.error() != null ? rawResult.error() : "Unknown downstream tool error";
            if (circuitBreaker != null) {
                if (ToolFailureClassifier.isDownstreamFailure(rawResult.cause(), rawResult.httpStatusCode(), error)) {
                    circuitBreaker.recordFailure();
                } else {
                    circuitBreaker.releasePermission();
                }
            }
            boolean indeterminate = isIndeterminate(rawResult.cause(), error);
            String outcome = indeterminate ? "INDETERMINATE" : "FAILED";
            if (auditRecorder != null) {
                auditRecorder.recordDirectToolExecution(context.agentId(), context.userId(), tool.name(), context.arguments(), decision.matchedRuleId(), decision.matchedRuleName(), decision.action().name(), rawResult.latencyMs(), outcome, error);
            }
            String sanitizedError = enrichedContext.hasHighClearance()
                    ? error
                    : piiRedactionGuard.redactString(error);
            Object truncatedError = responseTruncationGuard.truncate(sanitizedError);
            return ToolExecutionResult.failure(String.valueOf(truncatedError), rawResult.latencyMs());
        }

        // Direct execution success
        if (circuitBreaker != null) {
            circuitBreaker.onSuccess();
        }
        if (auditRecorder != null) {
            auditRecorder.recordDirectToolExecution(context.agentId(), context.userId(), tool.name(), context.arguments(), decision.matchedRuleId(), decision.matchedRuleName(), decision.action().name(), rawResult.latencyMs(), "SUCCESS", null);
        }

        // Clearance-aware output PII redaction (FR5)
        Object redactedData = piiRedactionGuard.redact(rawResult.data(), enrichedContext);

        // Oversized tool response truncation (FR5)
        Object truncatedData = responseTruncationGuard.truncate(redactedData);
        return ToolExecutionResult.success(truncatedData, rawResult.latencyMs());
    }

    private boolean isIndeterminate(Throwable t, String message) {
        if (t != null) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.util.concurrent.CancellationException
                    || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            Throwable cause = t.getCause();
            if (cause != null && isIndeterminate(cause, cause.getMessage())) {
                return true;
            }
        }
        if (message != null) {
            String lower = message.toLowerCase();
            return lower.contains("timed out")
                    || lower.contains("timeout")
                    || lower.contains("request cancelled")
                    || lower.contains("cancelled")
                    || lower.contains("resourceaccessexception")
                    || lower.contains("connection refused")
                    || lower.contains("connectexception")
                    || lower.contains("sockettimeoutexception")
                    || lower.contains("httptimeoutexception")
                    || lower.contains("httpconnecttimeoutexception")
                    || lower.contains("connection reset")
                    || lower.contains("indeterminate");
        }
        return false;
    }
}
