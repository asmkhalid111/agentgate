package com.agentgate.gateway.approval;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Human Approval State Persistence & Atomic Transitions Integration Test (FR4)")
class ApprovalIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. Create PENDING approval request with expiration and metadata")
    void createRequest_persistsPendingWithValidExpiration() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-1001", "amount", 5000.0, "currency", "BDT");

        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot",
                "user-corporate-99",
                "orders_refund",
                "pol-large-refund",
                args,
                Duration.ofMinutes(15)
        );

        assertNotNull(request.id());
        assertTrue(request.id().startsWith("appr-"));
        assertEquals("agent-finance-bot", request.agentId());
        assertEquals("user-corporate-99", request.userId());
        assertEquals("orders_refund", request.toolName());
        assertEquals("pol-large-refund", request.policyRuleId());
        assertEquals(ApprovalStatus.PENDING, request.status());
        assertNotNull(request.createdAt());
        assertNotNull(request.expiresAt());
        assertTrue(request.expiresAt().isAfter(request.createdAt()));
        assertNull(request.approvedArgumentsJson());

        // Verify stored in DB
        Optional<ApprovalRequest> fromDb = approvalService.findById(request.id());
        assertTrue(fromDb.isPresent());
        assertEquals(ApprovalStatus.PENDING, fromDb.get().status());

        JsonNode originalArgs = objectMapper.readTree(fromDb.get().originalArgumentsJson());
        assertEquals("ORD-1001", originalArgs.get("orderId").asText());
        assertEquals(5000.0, originalArgs.get("amount").asDouble());
    }

    @Test
    @DisplayName("2. Approve valid PENDING request transitions to APPROVED with original args copied")
    void approve_validPendingRequest_transitionsToApproved() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-1002", "amount", 4500.0);
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", args, Duration.ofMinutes(10)
        );

        ApprovalRequest approved = approvalService.approve(request.id(), "manager@agentgate.com", null);

        assertEquals(ApprovalStatus.APPROVED, approved.status());
        assertEquals("manager@agentgate.com", approved.decidedBy());
        assertNotNull(approved.decidedAt());

        // Approved arguments default to original arguments
        assertEquals(approved.originalArgumentsJson(), approved.approvedArgumentsJson());
    }

    @Test
    @DisplayName("3. Approve with edited arguments preserves original args and stores approved args separately (Reviewer Correction B)")
    void approve_withEditedArguments_storesOriginalAndApprovedSeparately() throws Exception {
        Map<String, Object> originalArgs = Map.of("orderId", "ORD-1003", "amount", 10000.0, "reason", "Damaged item");
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", originalArgs, Duration.ofMinutes(10)
        );

        // Reviewer reduces approved refund to 2,000 BDT
        Map<String, Object> editedArgs = Map.of("orderId", "ORD-1003", "amount", 2000.0, "reason", "Approved partial refund");
        ApprovalRequest approved = approvalService.approve(request.id(), "supervisor@agentgate.com", editedArgs);

        assertEquals(ApprovalStatus.APPROVED, approved.status());
        assertEquals("supervisor@agentgate.com", approved.decidedBy());

        // Original arguments must remain strictly preserved and untouched
        JsonNode origNode = objectMapper.readTree(approved.originalArgumentsJson());
        assertEquals(10000.0, origNode.get("amount").asDouble(), "Original arguments must be preserved");

        // Approved arguments must reflect the modified values
        JsonNode approvedNode = objectMapper.readTree(approved.approvedArgumentsJson());
        assertEquals(2000.0, approvedNode.get("amount").asDouble(), "Approved arguments must contain edited values");
        assertEquals("Approved partial refund", approvedNode.get("reason").asText());
    }

    @Test
    @DisplayName("4. Reject pending request transitions to REJECTED with reason and metadata")
    void reject_validPendingRequest_transitionsToRejected() {
        Map<String, Object> args = Map.of("orderId", "ORD-1004", "amount", 8000.0);
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", args, Duration.ofMinutes(10)
        );

        ApprovalRequest rejected = approvalService.reject(request.id(), "compliance@agentgate.com", "Insufficient audit documentation");

        assertEquals(ApprovalStatus.REJECTED, rejected.status());
        assertEquals("compliance@agentgate.com", rejected.decidedBy());
        assertEquals("Insufficient audit documentation", rejected.decisionReason());
        assertNotNull(rejected.decidedAt());
        assertNull(rejected.approvedArgumentsJson());
    }

    @Test
    @DisplayName("5. Cancel pending request transitions to CANCELLED")
    void cancel_validPendingRequest_transitionsToCancelled() {
        Map<String, Object> args = Map.of("orderId", "ORD-1005", "amount", 3000.0);
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", args, Duration.ofMinutes(10)
        );

        ApprovalRequest cancelled = approvalService.cancel(request.id(), "admin", "Caller aborted session");

        assertEquals(ApprovalStatus.CANCELLED, cancelled.status());
        assertEquals("admin", cancelled.decidedBy());
        assertEquals("Caller aborted session", cancelled.decisionReason());
        assertNotNull(cancelled.decidedAt());
    }

    @Test
    @DisplayName("6. Expire overdue requests sweeps all pending requests past expiration")
    void expirePendingRequests_sweepsOverdueRequests() {
        // Create a request with -5 minutes (already expired at creation)
        ApprovalRequest expiredRequest = new ApprovalRequest(
                "appr-expired-test-" + System.currentTimeMillis(),
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"orderId\":\"ORD-EXP\"}",
                null,
                ApprovalStatus.PENDING,
                Instant.now().minus(10, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES),
                null,
                null,
                null
        );
        repository.save(expiredRequest);

        int count = approvalService.expirePendingRequests();
        assertTrue(count >= 1, "Should have expired at least 1 overdue request");

        Optional<ApprovalRequest> updated = approvalService.findById(expiredRequest.id());
        assertTrue(updated.isPresent());
        assertEquals(ApprovalStatus.EXPIRED, updated.get().status());
        assertEquals("SYSTEM", updated.get().decidedBy());
    }

    @Test
    @DisplayName("7. Attempting to approve after expiration is rejected by atomic SQL check (Reviewer Correction A)")
    void approve_afterExpiration_rejectedByAtomicSqlCheck() {
        // Insert an expired request that is still marked PENDING in DB
        String expiredId = "appr-atomic-exp-" + System.currentTimeMillis();
        ApprovalRequest overduePending = new ApprovalRequest(
                expiredId,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"orderId\":\"ORD-OVERDUE\"}",
                null,
                ApprovalStatus.PENDING,
                Instant.now().minus(20, ChronoUnit.MINUTES),
                Instant.now().minus(10, ChronoUnit.MINUTES), // Expired 10m ago
                null,
                null,
                null
        );
        repository.save(overduePending);

        // Attempting to approve must fail because SQL requires expires_at > :now
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                approvalService.approve(expiredId, "manager@agentgate.com", null)
        );
        assertTrue(ex.getMessage().contains("expired"), "Exception must state request has expired");

        // Verify request is transitioned to EXPIRED, never APPROVED
        ApprovalRequest current = repository.findById(expiredId).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, current.status());
    }

    @Test
    @DisplayName("8. Attempting to reject after expiration is rejected by atomic SQL check (Reviewer Correction A)")
    void reject_afterExpiration_rejectedByAtomicSqlCheck() {
        String expiredId = "appr-atomic-exp-rej-" + System.currentTimeMillis();
        ApprovalRequest overduePending = new ApprovalRequest(
                expiredId,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"orderId\":\"ORD-OVERDUE-2\"}",
                null,
                ApprovalStatus.PENDING,
                Instant.now().minus(30, ChronoUnit.MINUTES),
                Instant.now().minus(15, ChronoUnit.MINUTES),
                null,
                null,
                null
        );
        repository.save(overduePending);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                approvalService.reject(expiredId, "compliance@agentgate.com", "Too late")
        );
        assertTrue(ex.getMessage().contains("expired"));

        ApprovalRequest current = repository.findById(expiredId).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, current.status());
    }

    @Test
    @DisplayName("9. Repeated or conflicting decisions are rejected (CAS enforcement)")
    void repeatedOrConflictingDecisions_rejectedByAtomicCas() {
        Map<String, Object> args = Map.of("orderId", "ORD-1009", "amount", 6000.0);
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", args, Duration.ofMinutes(10)
        );

        // First approval succeeds
        ApprovalRequest approved = approvalService.approve(request.id(), "first_approver", null);
        assertEquals(ApprovalStatus.APPROVED, approved.status());

        // Repeated approve fails
        IllegalStateException repeatedApprove = assertThrows(IllegalStateException.class, () ->
                approvalService.approve(request.id(), "second_approver", null)
        );
        assertTrue(repeatedApprove.getMessage().contains("APPROVED"));

        // Conflicting reject fails
        IllegalStateException conflictingReject = assertThrows(IllegalStateException.class, () ->
                approvalService.reject(request.id(), "rejecter", "Trying to reject approved request")
        );
        assertTrue(conflictingReject.getMessage().contains("APPROVED"));

        // Conflicting cancel fails
        IllegalStateException conflictingCancel = assertThrows(IllegalStateException.class, () ->
                approvalService.cancel(request.id(), "admin", "Trying to cancel approved request")
        );
        assertTrue(conflictingCancel.getMessage().contains("APPROVED"));
    }

    @Test
    @DisplayName("10. Find pending requests returns only active PENDING items")
    void findPending_returnsOnlyActivePendingRequests() {
        Map<String, Object> args = Map.of("orderId", "ORD-PENDING", "amount", 100.0);
        ApprovalRequest pendingReq = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund", args, Duration.ofMinutes(10)
        );

        List<ApprovalRequest> pendingList = approvalService.findPending();
        assertFalse(pendingList.isEmpty());
        assertTrue(pendingList.stream().anyMatch(r -> r.id().equals(pendingReq.id())));
        assertTrue(pendingList.stream().allMatch(r -> r.status() == ApprovalStatus.PENDING));
    }
}
