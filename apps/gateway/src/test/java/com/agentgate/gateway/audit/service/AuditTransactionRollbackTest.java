package com.agentgate.gateway.audit.service;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Audit Transaction Consistency & Rollback Tests (FR7 Reviewer Corrections)")
class AuditTransactionRollbackTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        auditRepository.resetToGenesis();
        approvalRepository.deleteAll();
    }

    @Test
    @DisplayName("Mode A: Rolled back approval transaction must NOT persist phantom audit record")
    void modeA_rolledBackApprovalTransaction_noPhantomAuditRecord() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> {
            tx.execute(status -> {
                // Creates approval request and records audit event in same transaction
                approvalService.createRequest(
                        "agent-test",
                        "user-1",
                        "tool-mutating",
                        "rule-1",
                        Map.of("key", "val"),
                        Duration.ofMinutes(15)
                );
                // Deliberately roll back
                throw new RuntimeException("Simulated business transaction failure");
            });
        });

        // Verify neither approval request nor audit event was committed
        assertThat(approvalRepository.findAll()).isEmpty();
        assertThat(auditRepository.count()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Mode A: Committed approval transaction must atomically persist state and audit record")
    void modeA_committedApprovalTransaction_persistsStateAndAudit() {
        ApprovalRequest req = approvalService.createRequest(
                "agent-test",
                "user-1",
                "tool-mutating",
                "rule-1",
                Map.of("key", "val"),
                Duration.ofMinutes(15)
        );

        // Created successfully
        assertThat(approvalRepository.findById(req.id())).isPresent();
        assertThat(auditRepository.count()).isEqualTo(1L);

        List<AuditEvent> events = auditRepository.findByApprovalId(req.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(events.get(0).decision()).isEqualTo("REQUIRE_APPROVAL");

        // Reject it (atomic state transition + audit event)
        approvalService.reject(req.id(), "admin.human", "Budget exceeded");

        assertThat(auditRepository.count()).isEqualTo(2L);
        List<AuditEvent> updatedEvents = auditRepository.findByApprovalId(req.id());
        assertThat(updatedEvents).hasSize(2);
        assertThat(updatedEvents.get(1).eventType()).isEqualTo("APPROVAL_DECISION");
        assertThat(updatedEvents.get(1).decision()).isEqualTo("REJECTED");
        assertThat(updatedEvents.get(1).actorId()).isEqualTo("admin.human");
    }

    @Test
    @DisplayName("Mode B: Independently committed pre-dispatch write intent must commit under REQUIRES_NEW")
    void modeB_preDispatchWriteIntent_commitsIndependently() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> {
            tx.execute(status -> {
                // Record write dispatch intent (Mode B: REQUIRES_NEW)
                auditRecorder.recordWriteDispatchIntent(
                        "agent-finance-bot",
                        "user-1",
                        "orders_refund",
                        Map.of("amount", 500),
                        "rule-refund",
                        "Refund Rule",
                        "appr-test-1"
                );
                // Outer transaction aborts
                throw new RuntimeException("Downstream network timeout simulation");
            });
        });

        // Even though the outer transaction aborted, the pre-dispatch intent WAS committed
        assertThat(auditRepository.count()).isEqualTo(1L);
        AuditEvent intent = auditRepository.findBySequenceNumber(1L).orElseThrow();
        assertThat(intent.eventType()).isEqualTo("WRITE_DISPATCH_INTENT");
        assertThat(intent.outcome()).isEqualTo("PENDING_DISPATCH");
        assertThat(intent.toolName()).isEqualTo("orders_refund");
    }

    @Test
    @DisplayName("Bulk cancellation: Kill switch activation creates individual audit events linked by approvalId")
    void bulkCancellation_createsIndividualAuditEventsLinkedByApprovalId() {
        // Create 2 pending approvals for agent-to-kill
        ApprovalRequest req1 = approvalService.createRequest(
                "agent-to-kill", "user-1", "tool-a", "rule-1", Map.of(), Duration.ofMinutes(15)
        );
        ApprovalRequest req2 = approvalService.createRequest(
                "agent-to-kill", "user-2", "tool-b", "rule-2", Map.of(), Duration.ofMinutes(15)
        );

        assertThat(auditRepository.count()).isEqualTo(2L); // 2 APPROVAL_REQUESTED events

        // Activate AGENT kill switch
        killSwitchService.activate(
                KillSwitchType.AGENT,
                "agent-to-kill",
                "Compromised behavior",
                "admin.security"
        );

        // Verify both pending requests were transitioned to CANCELLED
        assertThat(approvalRepository.findById(req1.id()).orElseThrow().status().name()).isEqualTo("CANCELLED");
        assertThat(approvalRepository.findById(req2.id()).orElseThrow().status().name()).isEqualTo("CANCELLED");

        // Verify per-approval audit events
        List<AuditEvent> req1Events = auditRepository.findByApprovalId(req1.id());
        assertThat(req1Events).hasSize(2);
        assertThat(req1Events.get(1).eventType()).isEqualTo("APPROVAL_DECISION");
        assertThat(req1Events.get(1).decision()).isEqualTo("CANCELLED");
        assertThat(req1Events.get(1).actorId()).isEqualTo("admin.security");

        List<AuditEvent> req2Events = auditRepository.findByApprovalId(req2.id());
        assertThat(req2Events).hasSize(2);
        assertThat(req2Events.get(1).eventType()).isEqualTo("APPROVAL_DECISION");
        assertThat(req2Events.get(1).decision()).isEqualTo("CANCELLED");
        assertThat(req2Events.get(1).actorId()).isEqualTo("admin.security");
    }
}
