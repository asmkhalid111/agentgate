package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Lightweight scheduled recovery mechanism for approved requests that remained in NOT_DISPATCHED
 * due to transient executor saturation or gateway restarts before claim (FR4).
 * <p>
 * Safety Invariants:
 * <ul>
 *   <li>Only scans for status = 'APPROVED' AND execution_state = 'NOT_DISPATCHED'.</li>
 *   <li>Uses the same atomic CAS claim as normal execution; no bypass.</li>
 *   <li>Never automatically retries records in DISPATCHED, COMPLETED, FAILED, or INDETERMINATE.</li>
 *   <li>If a crash occurred after claiming (DISPATCHED), it remains untouched to prevent duplicate side-effects.</li>
 * </ul>
 */
@Component
public class ApprovedExecutionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovedExecutionRecoveryScheduler.class);

    private final ApprovalRequestRepository repository;
    private final ApprovedToolExecutionService executionService;
    private final Executor executor;
    private final boolean recoveryEnabled;

    public ApprovedExecutionRecoveryScheduler(
            ApprovalRequestRepository repository,
            ApprovedToolExecutionService executionService,
            @Qualifier("approvedToolExecutionExecutor") Executor executor,
            @Value("${agentgate.approval.recovery.enabled:true}") boolean recoveryEnabled) {
        this.repository = repository;
        this.executionService = executionService;
        this.executor = executor;
        this.recoveryEnabled = recoveryEnabled;
    }

    @Scheduled(
            fixedDelayString = "${agentgate.approval.recovery-interval-ms:60000}",
            initialDelayString = "${agentgate.approval.recovery-initial-delay-ms:10000}"
    )
    public int recoverUndispatchedExecutions() {
        if (!recoveryEnabled) {
            return 0;
        }

        List<ApprovalRequest> undispatched = repository.findUndispatchedApproved(50);
        if (undispatched.isEmpty()) {
            return 0;
        }

        log.info("Found {} undispatched approved requests for execution recovery", undispatched.size());
        int scheduledCount = 0;
        for (ApprovalRequest req : undispatched) {
            try {
                executor.execute(() -> executionService.executeApproved(req.id()));
                scheduledCount++;
            } catch (Exception e) {
                log.warn("Failed to schedule recovery execution for approval request '{}': {}", req.id(), e.getMessage());
            }
        }
        return scheduledCount;
    }
}
