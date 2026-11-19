package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.event.ApprovalApprovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Asynchronous event listener that dispatches approved tool calls strictly after
 * the human approval transaction has successfully committed (FR4).
 */
@Component
public class ApprovedToolExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovedToolExecutionListener.class);

    private final ApprovedToolExecutionService executionService;

    public ApprovedToolExecutionListener(ApprovedToolExecutionService executionService) {
        this.executionService = executionService;
    }

    @Async("approvedToolExecutionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onApprovalApproved(ApprovalApprovedEvent event) {
        if (event == null || event.request() == null) {
            return;
        }
        String approvalId = event.request().id();
        log.info("Received ApprovalApprovedEvent for request '{}'. Initiating async execution.", approvalId);
        try {
            executionService.executeApproved(approvalId);
        } catch (Exception e) {
            log.error("Unhandled error during approved tool execution for request '{}': {}", approvalId, e.getMessage(), e);
        }
    }
}
