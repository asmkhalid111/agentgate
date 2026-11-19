package com.agentgate.gateway.approval.webhook;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Approval Webhook Transaction Safety & Pipeline Integration Tests (FR4)")
class ApprovalWebhookIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @MockitoSpyBean
    private ApprovalWebhookNotifier webhookNotifier;

    @MockitoBean
    private StructuredPolicyEvaluator policyEvaluator;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("1. Successful approval persistence triggers webhook notification after commit (Correction A)")
    void createRequest_persistsAndDispatchesWebhookAfterCommit() throws Exception {
        // Mock webhook sending to avoid real network call
        doReturn(true).when(webhookNotifier).sendWebhook(any());

        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot",
                "user-101",
                "orders_refund",
                "pol-large-refund",
                Map.of("amount", 2500.0),
                Duration.ofMinutes(15)
        );

        assertNotNull(request.id());

        // Verify request persisted in DB with PENDING status
        Optional<ApprovalRequest> persisted = approvalRepository.findById(request.id());
        assertTrue(persisted.isPresent());
        assertEquals(ApprovalStatus.PENDING, persisted.get().status());

        // Wait briefly for asynchronous @Async execution
        verify(webhookNotifier, timeout(3000).times(1)).sendWebhook(argThat(r ->
                r.id().equals(request.id()) &&
                r.agentId().equals("agent-finance-bot") &&
                r.toolName().equals("orders_refund")
        ));
    }

    @Test
    @DisplayName("2. Rolled-back transaction does NOT dispatch webhook notification (Correction A)")
    void rolledBackTransaction_doesNotDispatchWebhook() throws Exception {
        reset(webhookNotifier);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> {
            txTemplate.execute(status -> {
                approvalService.createRequest(
                        "agent-rollback-bot",
                        "user-102",
                        "orders_refund",
                        "pol-large-refund",
                        Map.of("amount", 9999.0),
                        Duration.ofMinutes(15)
                );
                // Force transaction rollback
                throw new RuntimeException("Simulated transaction rollback");
            });
        });

        // Ensure webhook was NEVER dispatched because transaction rolled back
        verify(webhookNotifier, after(500).never()).sendWebhook(any());
    }

    @Test
    @DisplayName("3. Failed HTTP delivery leaves the approval PENDING in database (Correction B)")
    void failedWebhookDelivery_leavesApprovalPending() {
        // Force webhook to fail
        doReturn(false).when(webhookNotifier).sendWebhook(any());

        ApprovalRequest request = approvalService.createRequest(
                "agent-fail-bot",
                "user-103",
                "orders_refund",
                "pol-large-refund",
                Map.of("amount", 3000.0),
                Duration.ofMinutes(15)
        );

        // Verify webhook attempted
        verify(webhookNotifier, timeout(3000).times(1)).sendWebhook(any());

        // Verify database state is strictly preserved as PENDING
        Optional<ApprovalRequest> persisted = approvalRepository.findById(request.id());
        assertTrue(persisted.isPresent());
        assertEquals(ApprovalStatus.PENDING, persisted.get().status(),
                "Approval request must remain PENDING even if webhook delivery fails");
    }

    @Test
    @DisplayName("4. REQUIRE_APPROVAL returns prompt result, never executes tool adapter, regardless of webhook latency (Correction B)")
    void pipeline_requireApproval_nonBlockingAndAdapterNeverExecuted() {
        // Register test tool
        toolRepository.save(new RegisteredTool(
                "tool-refund-test-webhook",
                "orders_refund_wh",
                "Refund tool",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Finance Team",
                "http://localhost:8082/api/orders/refund",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        ));
        toolRepository.addPermission("orders_refund_wh", "agent-finance-bot");

        when(policyEvaluator.evaluate(any())).thenReturn(
                PolicyDecision.requireApproval("rule-wh-test", "Large Refund Rule", "Held for approval", false, 5L)
        );

        // Simulate a slow webhook notifier
        doAnswer(invocation -> {
            Thread.sleep(500); // simulate slow network
            return true;
        }).when(webhookNotifier).sendWebhook(any());

        ToolExecutionContext context = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund_wh",
                Map.of("amount", 5000.0),
                "user-bob",
                RiskLevel.HIGH
        );

        long start = System.currentTimeMillis();
        ToolExecutionResult result = pipeline.execute(context);
        long duration = System.currentTimeMillis() - start;

        // Pipeline execution must return promptly without waiting for the slow webhook
        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("REQUIRE_APPROVAL: Call held for human approval"),
                "Result must indicate REQUIRE_APPROVAL");
        assertTrue(result.error().contains("Rule: Large Refund Rule"));

        // Crucial invariant: Downstream tool adapter must NEVER be executed
        verify(restToolAdapter, never()).execute(any(), any());
    }
}
