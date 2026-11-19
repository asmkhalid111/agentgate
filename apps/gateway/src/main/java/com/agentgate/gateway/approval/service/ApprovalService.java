package com.agentgate.gateway.approval.service;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the human approval request lifecycle and atomic state transitions (FR4).
 * <p>
 * Security & Governance Principles:
 * <ul>
 *   <li><b>Atomic Expiration Enforcement:</b> Expiration is enforced directly in the database update condition
 *       (expires_at &gt; now). Concurrent or overdue attempts fail deterministically.</li>
 *   <li><b>Privacy:</b> Argument payloads are never logged in application logs.</li>
 *   <li><b>Future Execution Safety:</b> When future approved execution is implemented, any edited arguments
 *       must be revalidated through the JsonSchemaValidationGuard and applicable authorization checks
 *       before downstream tool execution.</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private final ApprovalRequestRepository repository;
    private final ObjectMapper objectMapper;

    public ApprovalService(ApprovalRequestRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates and persists a new approval request in PENDING status.
     *
     * @param agentId requesting agent client identifier
     * @param userId optional end-user identity on whose behalf the agent acts
     * @param toolName high-risk tool name requiring approval
     * @param policyRuleId policy rule that mandated approval
     * @param arguments input arguments to be held
     * @param timeout duration until the request expires (defaults to 15m if null)
     * @return persisted ApprovalRequest
     */
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
                null
        );

        repository.save(request);

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
     *
     * @param id approval request identifier
     * @param decidedBy username of the approver
     * @param editedArguments optional edited arguments (null retains original arguments)
     * @return updated ApprovalRequest in APPROVED status
     * @throws IllegalStateException if request is expired, already decided, or in terminal state
     */
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
                    .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));

            if (current.isExpired(now) || (current.expiresAt() != null && !now.isBefore(current.expiresAt()))) {
                repository.transitionToExpired(id, now);
                throw new IllegalStateException("Cannot approve request '" + id + "': request has expired at " + current.expiresAt());
            }

            throw new IllegalStateException("Cannot approve request '" + id + "': request is in state " + current.status());
        }

        log.info("Approved request '{}' by '{}' [edited={}]", id, decidedBy, (approvedArgsJson != null));
        return repository.findById(id).orElseThrow();
    }

    /**
     * Atomically rejects a pending approval request.
     *
     * @param id approval request identifier
     * @param decidedBy username of the rejecter
     * @param reason explanation for the rejection
     * @return updated ApprovalRequest in REJECTED status
     * @throws IllegalStateException if request is expired, already decided, or in terminal state
     */
    public ApprovalRequest reject(String id, String decidedBy, String reason) {
        Instant now = Instant.now();
        int updated = repository.transitionToRejected(id, decidedBy, reason, now, now);
        if (updated == 0) {
            ApprovalRequest current = repository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));

            if (current.isExpired(now) || (current.expiresAt() != null && !now.isBefore(current.expiresAt()))) {
                repository.transitionToExpired(id, now);
                throw new IllegalStateException("Cannot reject request '" + id + "': request has expired at " + current.expiresAt());
            }

            throw new IllegalStateException("Cannot reject request '" + id + "': request is in state " + current.status());
        }

        log.info("Rejected approval request '{}' by '{}' [reason='{}']", id, decidedBy, reason);
        return repository.findById(id).orElseThrow();
    }

    /**
     * Atomically cancels a pending approval request.
     *
     * @param id approval request identifier
     * @param cancelledBy username or agent cancelling the request
     * @param reason explanation for cancellation
     * @return updated ApprovalRequest in CANCELLED status
     * @throws IllegalStateException if request is not in PENDING state
     */
    public ApprovalRequest cancel(String id, String cancelledBy, String reason) {
        Instant now = Instant.now();
        int updated = repository.transitionToCancelled(id, cancelledBy, reason, now);
        if (updated == 0) {
            ApprovalRequest current = repository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));

            throw new IllegalStateException("Cannot cancel request '" + id + "': request is in state " + current.status());
        }

        log.info("Cancelled approval request '{}' by '{}' [reason='{}']", id, cancelledBy, reason);
        return repository.findById(id).orElseThrow();
    }

    /**
     * Sweeps and marks all overdue PENDING requests as EXPIRED.
     *
     * @return number of requests transitioned to EXPIRED
     */
    public int expirePendingRequests() {
        Instant now = Instant.now();
        int count = repository.expirePendingRequests(now);
        if (count > 0) {
            log.info("Expired {} overdue approval requests at {}", count, now);
        }
        return count;
    }
}
