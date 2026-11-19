package com.agentgate.gateway.approval.service;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import com.agentgate.gateway.approval.dto.ApprovalDetailResponse;
import com.agentgate.gateway.approval.dto.ApprovalResultResponse;
import com.agentgate.gateway.approval.dto.ApprovalSummaryResponse;
import com.agentgate.gateway.approval.dto.PagedApprovalResponse;
import com.agentgate.gateway.approval.event.ApprovalApprovedEvent;
import com.agentgate.gateway.approval.event.ApprovalCreatedEvent;
import com.agentgate.gateway.approval.exception.ApprovalNotFoundException;
import com.agentgate.gateway.approval.exception.InvalidApprovalStateException;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service managing the human approval request lifecycle and atomic state
 * transitions (FR4).
 * <p>
 * Security & Governance Principles:
 * <ul>
 * <li><b>Atomic Expiration Enforcement:</b> Expiration is enforced directly in
 * the database update condition
 * (expires_at &gt; now). Concurrent or overdue attempts fail
 * deterministically.</li>
 * <li><b>Privacy:</b> Argument payloads are never logged in application
 * logs.</li>
 * <li><b>Future Execution Safety:</b> When future approved execution is
 * implemented, any edited arguments
 * must be revalidated through the JsonSchemaValidationGuard and applicable
 * authorization checks
 * before downstream tool execution.</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private final ApprovalRequestRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditRecorder auditRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalService(
            ApprovalRequestRepository repository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AuditRecorder auditRecorder) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.auditRecorder = auditRecorder;
    }

    public ApprovalService(
            ApprovalRequestRepository repository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this(repository, objectMapper, eventPublisher, null);
    }

    /**
     * Creates and persists a new approval request in PENDING status.
     * Enclosed in an atomic transaction to ensure event publication aligns
     * with database commit (Reviewer Correction A).
     *
     * @param agentId      requesting agent client identifier
     * @param userId       optional end-user identity on whose behalf the agent acts
     * @param toolName     high-risk tool name requiring approval
     * @param policyRuleId policy rule that mandated approval
     * @param arguments    input arguments to be held
     * @param timeout      duration until the request expires (defaults to 15m if
     *                     null)
     * @return persisted ApprovalRequest
     */
    @Transactional
    public ApprovalRequest createRequest(
            String agentId,
            String userId,
            String toolName,
            String policyRuleId,
            Map<String, Object> arguments,
            Duration timeout) {

        String id = "appr-" + UUID.randomUUID().toString();
        Instant now = Instant.now();
        Duration effectiveTimeout = (timeout != null && !timeout.isNegative() && !timeout.isZero())
                ? timeout
                : DEFAULT_TIMEOUT;
        Instant expiresAt = now.plus(effectiveTimeout);

        String originalArgsJson;
        try {
            originalArgsJson = arguments != null ? objectMapper.writeValueAsString(arguments) : "{}";
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize approval arguments: " + e.getMessage(), e);
        }

        ApprovalRequest request = new ApprovalRequest(
                id,
                agentId,
                userId,
                toolName,
                policyRuleId,
                originalArgsJson,
                null,
                ApprovalStatus.PENDING,
                now,
                expiresAt,
                null,
                null,
                null);

        repository.save(request);

        if (auditRecorder != null) {
            auditRecorder.recordApprovalRequested(
                    request.id(),
                    request.agentId(),
                    request.userId(),
                    request.toolName(),
                    request.originalArgumentsJson(),
                    request.policyRuleId(),
                    null
            );
        }

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ApprovalCreatedEvent(request));
        }

        // Security requirement: Never log raw arguments
        log.info("Created approval request '{}' for tool '{}' by agent '{}' [rule='{}', expiresAt={}]",
                id, toolName, agentId, policyRuleId, expiresAt);

        return request;
    }

    public Optional<ApprovalRequest> findById(String id) {
        return repository.findById(id);
    }

    public List<ApprovalRequest> findPending() {
        return repository.findByStatus(ApprovalStatus.PENDING);
    }

    /**
     * Atomically approves a pending approval request.
     * Enclosed in an atomic transaction to ensure event publication aligns
     * with database commit (Reviewer Condition 2).
     *
     * @param id              approval request identifier
     * @param decidedBy       username of the approver
     * @param editedArguments optional edited arguments (null retains original
     *                        arguments)
     * @return updated ApprovalRequest in APPROVED status
     * @throws IllegalStateException if request is expired, already decided, or in
     *                               terminal state
     */
    @Transactional(noRollbackFor = InvalidApprovalStateException.class)
    public ApprovalRequest approve(String id, String decidedBy, Map<String, Object> editedArguments) {
        Instant now = Instant.now();
        String approvedArgsJson = null;

        if (editedArguments != null && !editedArguments.isEmpty()) {
            try {
                approvedArgsJson = objectMapper.writeValueAsString(editedArguments);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize edited arguments: " + e.getMessage(), e);
            }
        }

        int updated = repository.transitionToApproved(id, approvedArgsJson, decidedBy, now, now);
        if (updated == 0) {
            ApprovalRequest current = repository.findById(id)
                    .orElseThrow(() -> new ApprovalNotFoundException("Approval request not found: " + id));

            if (current.isExpired(now) || (current.expiresAt() != null && !now.isBefore(current.expiresAt()))) {
                repository.transitionToExpired(id, now);
                throw new InvalidApprovalStateException(
                        "Cannot approve request '" + id + "': request has expired at " + current.expiresAt());
            }

            throw new InvalidApprovalStateException(
                    "Cannot approve request '" + id + "': request is in state " + current.status());
        }

        ApprovalRequest approved = repository.findById(id).orElseThrow();
        log.info("Approved request '{}' by '{}' [edited={}]", id, decidedBy, (approvedArgsJson != null));

        if (auditRecorder != null) {
            auditRecorder.recordApprovalDecision(
                    id,
                    approved.agentId(),
                    approved.userId(),
                    approved.toolName(),
                    approved.policyRuleId(),
                    "APPROVED",
                    decidedBy,
                    null,
                    approved.approvedArgumentsJson() != null ? approved.approvedArgumentsJson() : approved.originalArgumentsJson()
            );
        }

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ApprovalApprovedEvent(approved));
        }

        return approved;
    }

    /**
     * Atomically rejects a pending approval request.
     *
     * @param id        approval request identifier
     * @param decidedBy username of the rejecter
     * @param reason    explanation for the rejection
     * @return updated ApprovalRequest in REJECTED status
     * @throws IllegalStateException if request is expired, already decided, or in
     *                               terminal state
     */
    public ApprovalRequest reject(String id, String decidedBy, String reason) {
        Instant now = Instant.now();
        int updated = repository.transitionToRejected(id, decidedBy, reason, now, now);
        if (updated == 0) {
            ApprovalRequest current = repository.findById(id)
                    .orElseThrow(() -> new ApprovalNotFoundException("Approval request not found: " + id));

            if (current.isExpired(now) || (current.expiresAt() != null && !now.isBefore(current.expiresAt()))) {
                repository.transitionToExpired(id, now);
                throw new InvalidApprovalStateException(
                        "Cannot reject request '" + id + "': request has expired at " + current.expiresAt());
            }

            throw new InvalidApprovalStateException(
                    "Cannot reject request '" + id + "': request is in state " + current.status());
        }

        log.info("Rejected approval request '{}' by '{}' [reason='{}']", id, decidedBy, reason);
        ApprovalRequest rejected = repository.findById(id).orElseThrow();
        if (auditRecorder != null) {
            auditRecorder.recordApprovalDecision(
                    id,
                    rejected.agentId(),
                    rejected.userId(),
                    rejected.toolName(),
                    rejected.policyRuleId(),
                    "REJECTED",
                    decidedBy,
                    reason,
                    rejected.originalArgumentsJson()
            );
        }
        return rejected;
    }

    /**
     * Atomically cancels a pending approval request.
     *
     * @param id          approval request identifier
     * @param cancelledBy username or agent cancelling the request
     * @param reason      explanation for cancellation
     * @return updated ApprovalRequest in CANCELLED status
     * @throws IllegalStateException if request is not in PENDING state
     */
    public ApprovalRequest cancel(String id, String cancelledBy, String reason) {
        Instant now = Instant.now();
        int updated = repository.transitionToCancelled(id, cancelledBy, reason, now);
        if (updated == 0) {
            ApprovalRequest current = repository.findById(id)
                    .orElseThrow(() -> new ApprovalNotFoundException("Approval request not found: " + id));

            throw new InvalidApprovalStateException(
                    "Cannot cancel request '" + id + "': request is in state " + current.status());
        }

        log.info("Cancelled approval request '{}' by '{}' [reason='{}']", id, cancelledBy, reason);
        ApprovalRequest cancelled = repository.findById(id).orElseThrow();
        if (auditRecorder != null) {
            auditRecorder.recordApprovalDecision(
                    id,
                    cancelled.agentId(),
                    cancelled.userId(),
                    cancelled.toolName(),
                    cancelled.policyRuleId(),
                    "CANCELLED",
                    cancelledBy,
                    reason,
                    cancelled.originalArgumentsJson()
            );
        }
        return cancelled;
    }

    /**
     * Sweeps and marks all overdue PENDING requests as EXPIRED.
     * Scheduled to execute periodically with a fixed delay and initial boot delay.
     *
     * @return number of requests transitioned to EXPIRED
     */
    @Scheduled(fixedDelayString = "${agentgate.approval.expiration-interval-ms:60000}", initialDelayString = "${agentgate.approval.expiration-initial-delay-ms:5000}")
    public int expirePendingRequests() {
        Instant now = Instant.now();
        List<ApprovalRequest> expiredRequests = repository.findPendingExpired(now);
        int count = repository.expirePendingRequests(now);
        if (count > 0) {
            log.info("Expired {} overdue approval requests at {}", count, now);
            if (auditRecorder != null && expiredRequests != null) {
                for (ApprovalRequest req : expiredRequests) {
                    auditRecorder.recordApprovalDecision(
                            req.id(),
                            req.agentId(),
                            req.userId(),
                            req.toolName(),
                            req.policyRuleId(),
                            "EXPIRED",
                            "SYSTEM",
                            "Approval request expired after timeout",
                            req.originalArgumentsJson()
                    );
                }
            }
        }
        return count;
    }

    /**
     * Retrieves a paginated list of approval requests matching the given status.
     *
     * @param status status filter (null for all statuses)
     * @param page   zero-based page index
     * @param size   page size
     * @return PagedApprovalResponse containing lightweight summaries
     */
    public PagedApprovalResponse findPaged(ApprovalStatus status, int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        int offset = boundedPage * boundedSize;

        List<ApprovalRequest> requests = repository.findPaged(status, offset, boundedSize);
        long totalElements = repository.countByStatus(status);
        int totalPages = boundedSize > 0 ? (int) Math.ceil((double) totalElements / boundedSize) : 0;

        List<ApprovalSummaryResponse> summaries = requests.stream()
                .map(this::toSummaryResponse)
                .toList();

        return new PagedApprovalResponse(summaries, boundedPage, boundedSize, totalElements, totalPages);
    }

    public ApprovalSummaryResponse toSummaryResponse(ApprovalRequest req) {
        return new ApprovalSummaryResponse(
                req.id(),
                req.agentId(),
                req.userId(),
                req.toolName(),
                req.policyRuleId(),
                req.status().name(),
                req.createdAt(),
                req.expiresAt(),
                req.decidedAt(),
                req.decidedBy(),
                req.decisionReason());
    }

    public ApprovalDetailResponse toDetailResponse(ApprovalRequest req) {
        Map<String, Object> originalArgs = parseJsonMap(req.originalArgumentsJson());
        Map<String, Object> approvedArgs = parseJsonMap(req.approvedArgumentsJson());

        return new ApprovalDetailResponse(
                req.id(),
                req.agentId(),
                req.userId(),
                req.toolName(),
                req.policyRuleId(),
                req.status().name(),
                req.createdAt(),
                req.expiresAt(),
                req.decidedAt(),
                req.decidedBy(),
                req.decisionReason(),
                originalArgs,
                approvedArgs);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Retrieves the status and sanitized outcome of an approval request for the verified calling agent (FR4).
     * Enforces ownership: returns empty if request does not exist OR does not belong to callingAgentId
     * (providing a consistent non-disclosing response).
     *
     * @param approvalId identifier of the approval request
     * @param callingAgentId authenticated client identifier of the requesting agent
     * @return Optional containing ApprovalResultResponse if found and owned, otherwise empty
     */
    public Optional<ApprovalResultResponse> getApprovalResult(String approvalId, String callingAgentId) {
        if (approvalId == null || approvalId.isBlank() || callingAgentId == null || callingAgentId.isBlank()) {
            return Optional.empty();
        }
        Optional<ApprovalRequest> reqOpt = repository.findById(approvalId);
        if (reqOpt.isEmpty()) {
            return Optional.empty();
        }
        ApprovalRequest req = reqOpt.get();
        if (!callingAgentId.equals(req.agentId())) {
            log.warn("Security violation: Agent '{}' attempted to access approval '{}' belonging to agent '{}'",
                    callingAgentId, approvalId, req.agentId());
            return Optional.empty();
        }
        return Optional.of(toResultResponse(req));
    }

    private ApprovalResultResponse toResultResponse(ApprovalRequest req) {
        ApprovalStatus status = req.status();
        ExecutionState execState = req.executionState();

        return switch (status) {
            case PENDING -> new ApprovalResultResponse(
                    req.id(),
                    req.toolName(),
                    "PENDING",
                    execState != null ? execState.name() : ExecutionState.NOT_DISPATCHED.name(),
                    null,
                    null,
                    "Awaiting human approval decision",
                    req.createdAt(),
                    req.expiresAt(),
                    null,
                    null
            );
            case APPROVED -> {
                Object parsedResult = null;
                if (execState == ExecutionState.COMPLETED && req.executionResultJson() != null) {
                    parsedResult = parseResultData(req.executionResultJson());
                }
                String message = switch (execState) {
                    case NOT_DISPATCHED -> "Approved by human; awaiting execution";
                    case DISPATCHED -> "Execution in progress; outcome not yet recorded";
                    case COMPLETED -> "Tool executed successfully";
                    case FAILED -> "Execution failed";
                    case INDETERMINATE -> "Execution outcome is uncertain (e.g. timeout or disconnect). Do not retry automatically.";
                    case null -> null;
                };
                yield new ApprovalResultResponse(
                        req.id(),
                        req.toolName(),
                        "APPROVED",
                        execState != null ? execState.name() : ExecutionState.NOT_DISPATCHED.name(),
                        parsedResult,
                        (execState == ExecutionState.FAILED || execState == ExecutionState.INDETERMINATE) ? req.executionError() : null,
                        message,
                        req.createdAt(),
                        req.expiresAt(),
                        req.decidedAt(),
                        req.executedAt()
                );
            }
            case REJECTED -> new ApprovalResultResponse(
                    req.id(),
                    req.toolName(),
                    "REJECTED",
                    null,
                    null,
                    null,
                    "Request was rejected by human approver",
                    req.createdAt(),
                    req.expiresAt(),
                    req.decidedAt(),
                    null
            );
            case EXPIRED -> new ApprovalResultResponse(
                    req.id(),
                    req.toolName(),
                    "EXPIRED",
                    null,
                    null,
                    null,
                    "Approval request expired without a decision",
                    req.createdAt(),
                    req.expiresAt(),
                    req.decidedAt(),
                    null
            );
            case CANCELLED -> new ApprovalResultResponse(
                    req.id(),
                    req.toolName(),
                    "CANCELLED",
                    null,
                    null,
                    null,
                    "Approval request was cancelled",
                    req.createdAt(),
                    req.expiresAt(),
                    req.decidedAt(),
                    null
            );
        };
    }

    private Object parseResultData(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
