package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.policy.domain.PolicyDecision;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Approved Tool Safe Execution Comprehensive Tests (M03 Task 10A / FR4)")
class ApprovedToolExecutionTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private ApprovedToolExecutionService executionService;

    @Autowired
    private ApprovedExecutionRecoveryScheduler recoveryScheduler;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StructuredPolicyEvaluator policyEvaluator;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    private static final String TOOL_NAME = "approved_exec_test_tool";
    private static final String AGENT_ID = "agent-exec-bot";
    private static final String RULE_ID = "pol-rule-exec-1";

    @BeforeEach
    void setUp() {
        // Register standard test tool with schema requiring orderId (string) and amount (number <= 5000)
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": {"type": "string"},
                    "amount": {"type": "number", "maximum": 5000}
                  },
                  "required": ["orderId", "amount"]
                }
                """;

        RegisteredTool tool = new RegisteredTool(
                "tool-id-" + System.nanoTime(),
                TOOL_NAME,
                "Test execution tool",
                schema,
                RiskLevel.HIGH,
                "Core Engineering",
                "http://localhost:8082/api/test/exec",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(tool);
        toolRepository.addPermission(TOOL_NAME, AGENT_ID);

        // Default policy decision allows the approved tool rule
        when(policyEvaluator.evaluate(any())).thenReturn(
                PolicyDecision.requireApproval(RULE_ID, "Exec Rule", "Held for approval", false, 5L)
        );
    }

    private ApprovalRequest waitForTerminalExecution(String id) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            ApprovalRequest req = approvalRepository.findById(id).orElseThrow();
            if (req.executionState() != ExecutionState.NOT_DISPATCHED && req.executionState() != ExecutionState.DISPATCHED) {
                return req;
            }
            Thread.sleep(50);
        }
        return approvalRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("1. Original arguments execute after approval with COMPLETED state")
    void originalArguments_executeAfterApproval_persistsCompleted() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-101", "amount", 1200.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        doReturn(ToolExecutionResult.success(Map.of("status", "SUCCESS", "orderId", "ORD-101"), 20L))
                .when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-101".equals(ctx.arguments().get("orderId"))));

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ApprovalStatus.APPROVED, updated.status());
        assertEquals(ExecutionState.COMPLETED, updated.executionState());
        assertNotNull(updated.executedAt());
        assertTrue(updated.executionResultJson().contains("SUCCESS"));
        assertNull(updated.executionError());

        verify(restToolAdapter, atLeastOnce()).execute(any(), argThat(ctx ->
                "ORD-101".equals(ctx.arguments().get("orderId")) &&
                Double.valueOf(1200.0).equals(Double.valueOf(String.valueOf(ctx.arguments().get("amount"))))
        ));
    }

    @Test
    @DisplayName("2. Edited arguments execute after revalidation with COMPLETED state")
    void editedArguments_executeAfterRevalidation_persistsCompleted() throws Exception {
        Map<String, Object> origArgs = Map.of("orderId", "ORD-102", "amount", 4000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, origArgs, Duration.ofMinutes(10));

        doReturn(ToolExecutionResult.success(Map.of("status", "DISCOUNTED", "orderId", "ORD-102"), 15L))
                .when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-102".equals(ctx.arguments().get("orderId"))));

        Map<String, Object> editedArgs = Map.of("orderId", "ORD-102", "amount", 2500.0);
        approvalService.approve(req.id(), "supervisor@agentgate.com", editedArgs);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.COMPLETED, updated.executionState());
        assertTrue(updated.executionResultJson().contains("DISCOUNTED"));

        verify(restToolAdapter, atLeastOnce()).execute(any(), argThat(ctx ->
                Double.valueOf(2500.0).equals(Double.valueOf(String.valueOf(ctx.arguments().get("amount"))))
        ));
    }

    @Test
    @DisplayName("3. Invalid edited arguments are blocked before adapter invocation (fails closed)")
    void invalidEditedArguments_blockedBySchema_failsClosed() throws Exception {
        Map<String, Object> origArgs = Map.of("orderId", "ORD-103", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, origArgs, Duration.ofMinutes(10));

        // amount exceeds schema constraint maximum: 5000
        Map<String, Object> invalidEdited = Map.of("orderId", "ORD-103", "amount", 99999.0);
        approvalService.approve(req.id(), "supervisor@agentgate.com", invalidEdited);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertNotNull(updated.executionError());
        assertTrue(updated.executionError().contains("SCHEMA_VALIDATION_FAILED"));

        // Downstream adapter must NEVER be called with invalid arguments
        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> "ORD-103".equals(ctx.arguments().get("orderId"))));
    }

    @Test
    @DisplayName("4. Concurrent claims cause at most one downstream invocation (CAS protection)")
    void concurrentClaims_causeAtMostOneInvocation() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-104", "amount", 1500.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        AtomicInteger invocationCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            invocationCount.incrementAndGet();
            Thread.sleep(50);
            return ToolExecutionResult.success(Map.of("status", "OK"), 10L);
        }).when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-104".equals(ctx.arguments().get("orderId"))));

        // We manually transition to APPROVED in DB without triggering event, to control concurrency
        approvalRepository.transitionToApproved(req.id(), null, "manager@agentgate.com", Instant.now(), Instant.now());

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    executionService.executeApproved(req.id());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, invocationCount.get(), "Adapter must be executed exactly once despite concurrent claims");
        ApprovalRequest updated = approvalRepository.findById(req.id()).orElseThrow();
        assertEquals(ExecutionState.COMPLETED, updated.executionState());
    }

    @Test
    @DisplayName("5. Execution claim is committed before adapter invocation")
    void executionClaim_isCommittedBeforeAdapterInvocation() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-105", "amount", 800.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        AtomicReference<ExecutionState> stateDuringAdapterCall = new AtomicReference<>();
        CountDownLatch adapterCalled = new CountDownLatch(1);

        doAnswer(invocation -> {
            ApprovalRequest current = approvalRepository.findById(req.id()).orElseThrow();
            stateDuringAdapterCall.set(current.executionState());
            adapterCalled.countDown();
            return ToolExecutionResult.success(Map.of("status", "OK"), 10L);
        }).when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-105".equals(ctx.arguments().get("orderId"))));

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        assertTrue(adapterCalled.await(5, TimeUnit.SECONDS), "Adapter should be called within 5 seconds");
        assertEquals(ExecutionState.DISPATCHED, stateDuringAdapterCall.get(),
                "Database execution_state must already be committed as DISPATCHED when adapter runs");

        waitForTerminalExecution(req.id());
    }

    @Test
    @DisplayName("6. Disabled tool fails closed without adapter invocation")
    void disabledTool_failsClosed() throws Exception {
        String disabledToolName = "disabled_test_tool_" + System.nanoTime();
        RegisteredTool disabledTool = new RegisteredTool(
                "tool-dis-" + System.nanoTime(),
                disabledToolName,
                "Disabled tool",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Core",
                "http://localhost:8082/disabled",
                ProtocolType.REST,
                "POST",
                false, // disabled!
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(disabledTool);
        toolRepository.addPermission(disabledToolName, AGENT_ID);

        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", disabledToolName, RULE_ID, Map.of(), Duration.ofMinutes(10));
        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertTrue(updated.executionError().contains("currently disabled"));
        verify(restToolAdapter, never()).execute(argThat(t -> disabledToolName.equals(t.name())), any());
    }

    @Test
    @DisplayName("7. Revoked agent permission fails closed without adapter invocation")
    void revokedAgentPermission_failsClosed() throws Exception {
        String unauthAgent = "agent-revoked-bot-" + System.nanoTime();
        ApprovalRequest req = approvalService.createRequest(unauthAgent, "user-1", TOOL_NAME, RULE_ID,
                Map.of("orderId", "ORD-107", "amount", 500.0), Duration.ofMinutes(10));

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertTrue(updated.executionError().contains("not authorized to invoke tool"));
        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> unauthAgent.equals(ctx.agentId())));
    }

    @Test
    @DisplayName("8. Current DENY policy prevents dispatch (fails closed)")
    void currentDenyPolicy_preventsDispatch() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-108", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        // Policy engine returns DENY on re-evaluation
        when(policyEvaluator.evaluate(argThat(ctx -> "ORD-108".equals(ctx.arguments().get("orderId"))))).thenReturn(
                PolicyDecision.deny("rule-blacklist", "Blacklisted Customer", "Customer order frozen", false, 5L)
        );

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertTrue(updated.executionError().contains("POLICY_DENIED"));
        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> "ORD-108".equals(ctx.arguments().get("orderId"))));
    }

    @Test
    @DisplayName("9. New/different REQUIRE_APPROVAL rule prevents dispatch (fails closed)")
    void differentRequireApprovalRule_preventsDispatch() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-109", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        // Policy engine matches a DIFFERENT rule than RULE_ID (e.g. higher risk tier)
        when(policyEvaluator.evaluate(argThat(ctx -> "ORD-109".equals(ctx.arguments().get("orderId"))))).thenReturn(
                PolicyDecision.requireApproval("pol-rule-exec-tier2", "Tier 2 Approval", "Higher approval needed", false, 5L)
        );

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertTrue(updated.executionError().contains("REQUIRE_APPROVAL_NEW_RULE"));
        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> "ORD-109".equals(ctx.arguments().get("orderId"))));
    }

    @Test
    @DisplayName("10. Rejected, expired, cancelled and pending requests never execute")
    void nonApprovedRequests_neverExecute() {
        Map<String, Object> args = Map.of("orderId", "ORD-110", "amount", 500.0);

        // A. PENDING request
        ApprovalRequest pending = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));
        executionService.executeApproved(pending.id());
        assertEquals(ApprovalStatus.PENDING, approvalRepository.findById(pending.id()).orElseThrow().status());
        assertEquals(ExecutionState.NOT_DISPATCHED, approvalRepository.findById(pending.id()).orElseThrow().executionState());

        // B. REJECTED request
        ApprovalRequest toReject = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));
        approvalService.reject(toReject.id(), "manager@agentgate.com", "Rejected by manager");
        executionService.executeApproved(toReject.id());
        assertEquals(ApprovalStatus.REJECTED, approvalRepository.findById(toReject.id()).orElseThrow().status());
        assertEquals(ExecutionState.NOT_DISPATCHED, approvalRepository.findById(toReject.id()).orElseThrow().executionState());

        // C. CANCELLED request
        ApprovalRequest toCancel = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));
        approvalService.cancel(toCancel.id(), AGENT_ID, "Cancelled by agent");
        executionService.executeApproved(toCancel.id());
        assertEquals(ApprovalStatus.CANCELLED, approvalRepository.findById(toCancel.id()).orElseThrow().status());
        assertEquals(ExecutionState.NOT_DISPATCHED, approvalRepository.findById(toCancel.id()).orElseThrow().executionState());

        // D. EXPIRED request (via past expiration)
        ApprovalRequest expired = new ApprovalRequest(
                "appr-expired-" + System.nanoTime(), AGENT_ID, "user-1", TOOL_NAME, RULE_ID,
                "{\"orderId\":\"ORD-110\",\"amount\":500.0}", null, ApprovalStatus.EXPIRED,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(50),
                Instant.now().minusSeconds(50), "SYSTEM", "Expired",
                ExecutionState.NOT_DISPATCHED, null, null, null
        );
        approvalRepository.save(expired);
        executionService.executeApproved(expired.id());
        assertEquals(ExecutionState.NOT_DISPATCHED, approvalRepository.findById(expired.id()).orElseThrow().executionState());

        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> "ORD-110".equals(ctx.arguments().get("orderId"))));
    }

    @Test
    @DisplayName("11. Downstream timeout produces INDETERMINATE without retry")
    void downstreamTimeout_producesIndeterminate_withoutRetry() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-111", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        doThrow(new ResourceAccessException("Connection timed out", new SocketTimeoutException("Read timed out")))
                .when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-111".equals(ctx.arguments().get("orderId"))));

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.INDETERMINATE, updated.executionState());
        assertNotNull(updated.executionError());
        assertTrue(updated.executionError().toLowerCase().contains("timed out") || updated.executionError().contains("INDETERMINATE"));

        // Subsequent execution attempt must NOT retry
        reset(restToolAdapter);
        executionService.executeApproved(req.id());
        verify(restToolAdapter, never()).execute(any(), argThat(ctx -> "ORD-111".equals(ctx.arguments().get("orderId"))));
    }

    @Test
    @DisplayName("12. PII is redacted and existing truncation is applied before storage")
    void piiRedactionAndTruncation_appliedBeforeStorage() throws Exception {
        Map<String, Object> args = Map.of("orderId", "ORD-112", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        // Return sensitive Bangladeshi customer PII in response
        Map<String, Object> rawData = Map.of(
                "customerEmail", "secret.officer@domain.com",
                "customerPhone", "01712345678",
                "customerNid", "19901234567890123",
                "status", "REFUNDED"
        );
        doReturn(ToolExecutionResult.success(rawData, 25L))
                .when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-112".equals(ctx.arguments().get("orderId"))));

        approvalService.approve(req.id(), "manager@agentgate.com", null);

        ApprovalRequest updated = waitForTerminalExecution(req.id());
        assertEquals(ExecutionState.COMPLETED, updated.executionState());
        String resultJson = updated.executionResultJson();

        // Verify PII is permanently masked in persisted JSON column
        assertFalse(resultJson.contains("secret.officer@domain.com"));
        assertFalse(resultJson.contains("01712345678"));
        assertFalse(resultJson.contains("19901234567890123"));
        assertTrue(resultJson.contains("[REDACTED_EMAIL]"));
        assertTrue(resultJson.contains("[REDACTED_PHONE]"));
        assertTrue(resultJson.contains("[REDACTED_NID]"));
    }

    @Test
    @DisplayName("13. Human approval HTTP response does not wait for downstream latency")
    void humanApproval_doesNotWaitForDownstreamLatency() {
        Map<String, Object> args = Map.of("orderId", "ORD-113", "amount", 1000.0);
        ApprovalRequest req = approvalService.createRequest(AGENT_ID, "user-1", TOOL_NAME, RULE_ID, args, Duration.ofMinutes(10));

        // Simulate 2000ms downstream tool latency
        doAnswer(invocation -> {
            Thread.sleep(2000);
            return ToolExecutionResult.success(Map.of("status", "OK"), 2000L);
        }).when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-113".equals(ctx.arguments().get("orderId"))));

        long start = System.currentTimeMillis();
        ApprovalRequest approved = approvalService.approve(req.id(), "manager@agentgate.com", null);
        long duration = System.currentTimeMillis() - start;

        assertEquals(ApprovalStatus.APPROVED, approved.status());
        // Human approve call must return promptly (well under the 2-second sleep)
        assertTrue(duration < 800, "ApprovalService.approve() took " + duration + "ms, which is too slow and blocks the approver");
    }

    @Test
    @DisplayName("14. Undispatched approvals are safely recoverable by background scheduler")
    void undispatchedApprovals_safelyRecoveredByScheduler() {
        String missedId = "appr-missed-" + System.nanoTime();
        ApprovalRequest missed = new ApprovalRequest(
                missedId, AGENT_ID, "user-1", TOOL_NAME, RULE_ID,
                "{\"orderId\":\"ORD-114\",\"amount\":1000.0}", null,
                ApprovalStatus.APPROVED,
                Instant.now().minusSeconds(120), Instant.now().plusSeconds(600),
                Instant.now().minusSeconds(60), "manager@agentgate.com", null,
                ExecutionState.NOT_DISPATCHED, null, null, null
        );
        approvalRepository.save(missed);

        doReturn(ToolExecutionResult.success(Map.of("status", "RECOVERED"), 10L))
                .when(restToolAdapter).execute(any(), argThat(ctx -> "ORD-114".equals(ctx.arguments().get("orderId"))));

        int recoveredCount = recoveryScheduler.recoverUndispatchedExecutions();
        assertTrue(recoveredCount >= 1, "Recovery scheduler should pick up undispatched approved request");

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 30; i++) {
                ApprovalRequest current = approvalRepository.findById(missedId).orElseThrow();
                if (current.executionState() == ExecutionState.COMPLETED) {
                    return;
                }
                Thread.sleep(100);
            }
        });

        ApprovalRequest finalReq = approvalRepository.findById(missedId).orElseThrow();
        assertEquals(ExecutionState.COMPLETED, finalReq.executionState());
    }

    @Test
    @DisplayName("15. Crashed/claimed approvals are never automatically re-dispatched")
    void crashedOrClaimedApprovals_neverReDispatched() {
        // Record left in DISPATCHED (e.g. gateway crashed mid-call)
        String crashedId = "appr-crashed-" + System.nanoTime();
        ApprovalRequest crashed = new ApprovalRequest(
                crashedId, AGENT_ID, "user-1", TOOL_NAME, RULE_ID,
                "{\"orderId\":\"ORD-115\",\"amount\":1000.0}", null,
                ApprovalStatus.APPROVED,
                Instant.now().minusSeconds(120), Instant.now().plusSeconds(600),
                Instant.now().minusSeconds(60), "manager@agentgate.com", null,
                ExecutionState.DISPATCHED, Instant.now().minusSeconds(50), null, null
        );
        approvalRepository.save(crashed);

        // Record left in INDETERMINATE
        String indeterminateId = "appr-indet-" + System.nanoTime();
        ApprovalRequest indeterminate = new ApprovalRequest(
                indeterminateId, AGENT_ID, "user-1", TOOL_NAME, RULE_ID,
                "{\"orderId\":\"ORD-116\",\"amount\":1000.0}", null,
                ApprovalStatus.APPROVED,
                Instant.now().minusSeconds(120), Instant.now().plusSeconds(600),
                Instant.now().minusSeconds(60), "manager@agentgate.com", null,
                ExecutionState.INDETERMINATE, Instant.now().minusSeconds(50), null, "Timeout"
        );
        approvalRepository.save(indeterminate);

        // Run recovery sweeper
        recoveryScheduler.recoverUndispatchedExecutions();

        // Downstream adapter must NEVER be called for DISPATCHED or INDETERMINATE
        verify(restToolAdapter, never()).execute(any(), argThat(ctx ->
                "ORD-115".equals(ctx.arguments().get("orderId")) ||
                "ORD-116".equals(ctx.arguments().get("orderId"))
        ));

        // State remains untouched
        assertEquals(ExecutionState.DISPATCHED, approvalRepository.findById(crashedId).orElseThrow().executionState());
        assertEquals(ExecutionState.INDETERMINATE, approvalRepository.findById(indeterminateId).orElseThrow().executionState());
    }
}
