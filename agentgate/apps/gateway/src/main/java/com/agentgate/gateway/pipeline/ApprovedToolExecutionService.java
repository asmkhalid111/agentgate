package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.guard.ValidationResult;
import com.agentgate.gateway.killswitch.dto.KillSwitchCheckResult;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.ratelimit.WriteCapDecision;
import com.agentgate.gateway.ratelimit.WriteCapLimiter;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service managing the atomic claim, pre-execution governance revalidation,
 * downstream dispatch, privacy sanitization, and terminal state persistence
 * for human-approved tool calls (FR4).
 */
@Service
public class ApprovedToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApprovedToolExecutionService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ApprovalRequestRepository approvalRepository;
    private final RegisteredToolRepository toolRepository;
    private final List<ToolAdapter> adapters;
    private final StructuredPolicyEvaluator policyEvaluator;
    private final JsonSchemaValidationGuard validationGuard;
    private final PiiRedactionGuard piiRedactionGuard;
    private final ResponseTruncationGuard responseTruncationGuard;
    private final ObjectMapper objectMapper;
    private final WriteCapLimiter writeCapLimiter;
    private final KillSwitchService killSwitchService;
    private final AuditRecorder auditRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovedToolExecutionService(
            ApprovalRequestRepository approvalRepository,
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ObjectMapper objectMapper,
            WriteCapLimiter writeCapLimiter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) KillSwitchService killSwitchService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AuditRecorder auditRecorder) {
        this.approvalRepository = approvalRepository;
        this.toolRepository = toolRepository;
        this.adapters = adapters;
        this.policyEvaluator = policyEvaluator;
        this.validationGuard = validationGuard;
        this.piiRedactionGuard = piiRedactionGuard;
        this.responseTruncationGuard = responseTruncationGuard;
        this.objectMapper = objectMapper;
        this.writeCapLimiter = writeCapLimiter;
        this.killSwitchService = killSwitchService;
        this.auditRecorder = auditRecorder;
    }

    public ApprovedToolExecutionService(
            ApprovalRequestRepository approvalRepository,
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ObjectMapper objectMapper,
            WriteCapLimiter writeCapLimiter,
            KillSwitchService killSwitchService) {
        this(approvalRepository, toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, objectMapper, writeCapLimiter, killSwitchService, null);
    }

    public ApprovedToolExecutionService(
            ApprovalRequestRepository approvalRepository,
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ObjectMapper objectMapper,
            WriteCapLimiter writeCapLimiter) {
        this(approvalRepository, toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, objectMapper, writeCapLimiter, null, null);
    }

    public ApprovedToolExecutionService(
            ApprovalRequestRepository approvalRepository,
            RegisteredToolRepository toolRepository,
            List<ToolAdapter> adapters,
            StructuredPolicyEvaluator policyEvaluator,
            JsonSchemaValidationGuard validationGuard,
            PiiRedactionGuard piiRedactionGuard,
            ResponseTruncationGuard responseTruncationGuard,
            ObjectMapper objectMapper) {
        this(approvalRepository, toolRepository, adapters, policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard, objectMapper, null, null, null);
    }

    /**
     * Executes a human-approved tool request with at-most-once downstream dispatch.
     * <p>
     * Architecture & Safety Invariants:
     * <ul>
     *   <li><b>Atomic Claim:</b> Executed via SQL CAS update before adapter dispatch.</li>
     *   <li><b>Transaction Boundaries:</b> The claim transaction is committed before downstream invocation.
     *       No database transaction is held open during downstream tool execution.</li>
     *   <li><b>Governance Revalidation:</b> Verifies tool enabled, agent authorization, JSON Schema,
     *       and re-evaluates active policies (enforcing DENY and preventing new approval bypass).</li>
     *   <li><b>Privacy & Redaction:</b> Assumes low clearance on background worker; strictly redacts PII
     *       and truncates oversized output before persistence.</li>
     *   <li><b>Indeterminate Handling:</b> Downstream timeouts or network disconnects transition to INDETERMINATE
     *       and are NEVER automatically retried.</li>
     * </ul>
     *
     * @param approvalId identifier of the approval request
     */
    public void executeApproved(String approvalId) {
        Instant now = Instant.now();

        // 1. Atomic CAS Claim: Claim execution ownership before anything else.
        // Returns 1 if claimed, 0 if already claimed or ineligible.
        int claimed = approvalRepository.claimExecution(approvalId, now);
        if (claimed == 0) {
            log.debug("Execution claim skipped for request '{}' (already claimed or not APPROVED)", approvalId);
            return;
        }

        log.info("Claimed approval request '{}' for execution. Rechecking governance...", approvalId);

        // 2. Fetch fresh record
        ApprovalRequest request = approvalRepository.findById(approvalId).orElse(null);
        if (request == null) {
            log.error("Approval request '{}' claimed but missing from repository", approvalId);
            return;
        }

        String effectiveArgsJson = (request.approvedArgumentsJson() != null && !request.approvedArgumentsJson().isBlank())
                ? request.approvedArgumentsJson()
                : (request.originalArgumentsJson() != null ? request.originalArgumentsJson() : "{}");

        // 3. Pre-dispatch Governance Revalidation
        // 3a. Tool Existence & Enabled Check
        Optional<RegisteredTool> toolOpt = toolRepository.findByName(request.toolName());
        if (toolOpt.isEmpty()) {
            log.warn("Approved tool execution failed: Unknown tool '{}' for approval '{}'", request.toolName(), approvalId);
            String err = "Unknown tool: " + request.toolName();
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), request.toolName(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, err);
            }
            return;
        }
        RegisteredTool tool = toolOpt.get();

        if (!tool.enabled()) {
            log.warn("Approved tool execution failed: Tool '{}' is currently disabled for approval '{}'", tool.name(), approvalId);
            String err = "Tool '" + tool.name() + "' is currently disabled";
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, err);
            }
            return;
        }

        // 3b. Direct Agent Permission Check
        if (!toolRepository.isAgentPermitted(tool.name(), request.agentId())) {
            log.warn("Approved tool execution failed: Agent '{}' is unauthorized for tool '{}' in approval '{}'",
                    request.agentId(), tool.name(), approvalId);
            String err = "Agent '" + request.agentId() + "' is not authorized to invoke tool '" + tool.name() + "'";
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, err);
            }
            return;
        }

        // 3c. Parse Arguments and Revalidate JSON Schema
        Map<String, Object> arguments;
        try {
            arguments = parseArgumentsJson(effectiveArgsJson);
        } catch (Exception e) {
            log.warn("Approved tool execution failed: Argument deserialization error for approval '{}': {}", approvalId, e.getMessage());
            String err = "SCHEMA_VALIDATION_FAILED: Invalid arguments JSON: " + e.getMessage();
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, err);
            }
            return;
        }

        ValidationResult validation = validationGuard.validate(tool, arguments);
        if (!validation.valid()) {
            log.warn("Approved tool execution failed: Schema validation rejected arguments for tool '{}' in approval '{}': {}",
                    tool.name(), approvalId, validation.error());
            approvalRepository.recordExecutionFailure(approvalId, validation.error(), Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, validation.error());
            }
            return;
        }

        // 3d. Current Policy Re-evaluation using trusted persisted identity
        ToolExecutionContext context = new ToolExecutionContext(
                request.agentId(),
                tool.name(),
                arguments,
                request.userId(),
                tool.riskLevel(),
                Collections.emptySet() // Background worker strictly assumes low clearance
        );

        PolicyDecision decision = policyEvaluator.evaluate(context);
        if (decision.action() == PolicyAction.DENY) {
            log.warn("Approved tool execution blocked by current DENY policy rule '{}': {}", decision.matchedRuleName(), decision.reason());
            String err = "POLICY_DENIED: " + decision.reason();
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", decision.evaluationTimeMs(), err);
            }
            return;
        }

        if (decision.action() == PolicyAction.REQUIRE_APPROVAL) {
            if (decision.matchedRuleId() != null && decision.matchedRuleId().equals(request.policyRuleId())) {
                log.info("Original approval rule '{}' satisfied for request '{}'", request.policyRuleId(), approvalId);
            } else {
                log.warn("Approved tool execution blocked: Re-evaluation matched different approval rule '{}' (expected '{}')",
                        decision.matchedRuleId(), request.policyRuleId());
                String err = "REQUIRE_APPROVAL_NEW_RULE: Re-evaluation matched different approval rule " + decision.matchedRuleId();
                approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
                if (auditRecorder != null) {
                    auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", decision.evaluationTimeMs(), err);
                }
                return;
            }
        }

        // 4. Hourly Write Cap Check for write tools (FR6)
        if (tool.isWriteOperation() && writeCapLimiter != null) {
            WriteCapDecision writeDecision = writeCapLimiter.tryAcquireWrite(request.agentId());
            if (!writeDecision.allowed()) {
                log.warn("Write cap rejected approved execution for request '{}' [agent='{}', tool='{}']: {}",
                        approvalId, request.agentId(), tool.name(), writeDecision.message());
                approvalRepository.recordExecutionFailure(approvalId, writeDecision.message(), Instant.now());
                if (auditRecorder != null) {
                    auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, writeDecision.message());
                }
                return;
            }
        }

        // 5. Emergency Kill Switch Check immediately pre-dispatch (FR8)
        // Catches switches activated while the approval request was pending
        if (killSwitchService != null) {
            KillSwitchCheckResult killDecision = killSwitchService.checkBlocked(
                    request.agentId(),
                    tool.name(),
                    tool.isWriteOperation()
            );
            if (killDecision.blocked()) {
                log.warn("Approved tool execution blocked by emergency kill switch for request '{}' [agent='{}', tool='{}']: {}",
                        approvalId, request.agentId(), tool.name(), killDecision.message());
                approvalRepository.recordExecutionFailure(approvalId, killDecision.message(), Instant.now());
                if (auditRecorder != null) {
                    auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, killDecision.message());
                }
                return;
            }
        }

        // 6. Pre-Dispatch Write Intent for write operations (Mandatory Reviewer Requirement 4)
        if (tool.isWriteOperation() && auditRecorder != null) {
            try {
                auditRecorder.recordWriteDispatchIntent(
                        request.agentId(),
                        request.userId(),
                        tool.name(),
                        arguments,
                        request.policyRuleId(),
                        null,
                        approvalId
                );
            } catch (Exception e) {
                log.error("Failed to commit pre-dispatch write intent audit event for approval '{}': {}", approvalId, e.getMessage());
                approvalRepository.recordExecutionFailure(approvalId,
                        "AUDIT_WRITE_INTENT_PERSISTENCE_FAILED: Pre-dispatch audit logging failed; tool was not executed.",
                        Instant.now());
                return;
            }
        }

        // 7. Downstream Adapter Dispatch
        ToolAdapter adapter = adapters.stream()
                .filter(a -> a.supports(tool.protocolType()))
                .findFirst()
                .orElse(null);

        if (adapter == null) {
            log.error("No adapter supports protocol '{}' for tool '{}'", tool.protocolType(), tool.name());
            String err = "Unsupported protocol: " + tool.protocolType();
            approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "FAILED", 0L, err);
            }
            return;
        }

        ToolExecutionResult rawResult;
        try {
            rawResult = adapter.execute(tool, context);
        } catch (Throwable t) {
            log.error("Exception during tool adapter execution for approval '{}': {}", approvalId, t.getMessage());
            boolean indeterminate = isIndeterminate(t, t.getMessage());
            String err = (indeterminate ? "INDETERMINATE: " : "EXECUTION_EXCEPTION: ") + t.getMessage();
            if (indeterminate) {
                approvalRepository.recordExecutionIndeterminate(approvalId, err, Instant.now());
            } else {
                approvalRepository.recordExecutionFailure(approvalId, err, Instant.now());
            }
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), indeterminate ? "INDETERMINATE" : "FAILED", 0L, err);
            }
            return;
        }

        // 8. Outcome Persistence & Privacy Sanitization
        if (rawResult.success()) {
            // Clearance-aware output PII redaction (strictly low clearance)
            Object redactedData = piiRedactionGuard.redact(rawResult.data(), context);
            // Oversized tool response truncation (64 KB UTF-8, 100 array items)
            Object truncatedData = responseTruncationGuard.truncate(redactedData);
            String resultJson = serializeResult(truncatedData);

            approvalRepository.recordExecutionSuccess(approvalId, resultJson, Instant.now());
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), "SUCCESS", rawResult.latencyMs(), null);
            }
            log.info("Approved tool execution COMPLETED for request '{}' [latency={}ms]", approvalId, rawResult.latencyMs());
        } else {
            String error = rawResult.error() != null ? rawResult.error() : "Unknown downstream tool error";
            String sanitizedError = piiRedactionGuard.redactString(error);
            Object truncatedError = responseTruncationGuard.truncate(sanitizedError);
            String finalError = String.valueOf(truncatedError);
            boolean indeterminate = isIndeterminate(null, error);

            if (indeterminate) {
                approvalRepository.recordExecutionIndeterminate(approvalId, finalError, Instant.now());
                log.warn("Approved tool execution INDETERMINATE for request '{}': {}", approvalId, finalError);
            } else {
                approvalRepository.recordExecutionFailure(approvalId, finalError, Instant.now());
                log.warn("Approved tool execution FAILED for request '{}': {}", approvalId, finalError);
            }
            if (auditRecorder != null) {
                auditRecorder.recordApprovedExecution(approvalId, request.agentId(), request.userId(), tool.name(), effectiveArgsJson, request.policyRuleId(), request.decidedBy(), indeterminate ? "INDETERMINATE" : "FAILED", rawResult.latencyMs(), finalError);
            }
        }
    }

    private boolean isIndeterminate(Throwable t, String message) {
        if (t != null) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof org.springframework.web.client.ResourceAccessException
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
                    || lower.contains("resourceaccessexception")
                    || lower.contains("connection refused")
                    || lower.contains("connectexception")
                    || lower.contains("sockettimeoutexception")
                    || lower.contains("connection reset")
                    || lower.contains("indeterminate");
        }
        return false;
    }

    private String serializeResult(Object data) {
        if (data == null) {
            return "{}";
        }
        if (data instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize execution result object: {}", e.getMessage());
            return String.valueOf(data);
        }
    }

    private Map<String, Object> parseArgumentsJson(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }
}
