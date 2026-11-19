package com.agentgate.gateway.killswitch;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import com.agentgate.gateway.approval.dto.ApprovalResultResponse;
import com.agentgate.gateway.approval.exception.InvalidApprovalStateException;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.dto.KillSwitchCheckResult;
import com.agentgate.gateway.killswitch.repository.KillSwitchRepository;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.pipeline.ApprovedToolExecutionService;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Emergency Kill Switches Integration Tests (FR8)")
class KillSwitchIntegrationTest {

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private ApprovedToolExecutionService approvedExecutionService;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private KillSwitchRepository killSwitchRepository;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ApprovalService approvalService;

    @MockitoSpyBean
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private StructuredPolicyEvaluator policyEvaluator;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    @Autowired
    private JdbcClient jdbcClient;

    private static final String AGENT_ALPHA = "agent-alpha";
    private static final String AGENT_BETA = "agent-beta";

    private static final String TOOL_WRITE = "ks_write_tool";
    private static final String TOOL_READ = "ks_read_tool";
    private static final String TOOL_HIGH_RISK = "ks_high_risk_tool";

    private static final String ADMIN_USER = "admin.security";

    @BeforeEach
    void setUp() {
        killSwitchRepository.deleteAll();
        approvalRepository.deleteAll();

        // Configure default tool adapter mock
        doReturn(ToolExecutionResult.success(Map.of("status", "ok"), 10L))
                .when(restToolAdapter).execute(any(), any());

        // Register write tool (POST)
        RegisteredTool writeTool = new RegisteredTool(
                "tool-write-" + System.nanoTime(),
                TOOL_WRITE,
                "Write Tool",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}",
                RiskLevel.LOW,
                "Core Engineering",
                "http://localhost:8082/api/test/write",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(writeTool);
        toolRepository.addPermission(TOOL_WRITE, AGENT_ALPHA);
        toolRepository.addPermission(TOOL_WRITE, AGENT_BETA);

        // Register read tool (GET)
        RegisteredTool readTool = new RegisteredTool(
                "tool-read-" + System.nanoTime(),
                TOOL_READ,
                "Read Tool",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
                RiskLevel.LOW,
                "Core Engineering",
                "http://localhost:8082/api/test/read",
                ProtocolType.REST,
                "GET",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(readTool);
        toolRepository.addPermission(TOOL_READ, AGENT_ALPHA);
        toolRepository.addPermission(TOOL_READ, AGENT_BETA);

        // Register high risk tool for approvals
        RegisteredTool highRiskTool = new RegisteredTool(
                "tool-hr-" + System.nanoTime(),
                TOOL_HIGH_RISK,
                "High Risk Tool",
                "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}},\"required\":[\"amount\"]}",
                RiskLevel.HIGH,
                "Finance",
                "http://localhost:8082/api/test/finance",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(highRiskTool);
        toolRepository.addPermission(TOOL_HIGH_RISK, AGENT_ALPHA);
        toolRepository.addPermission(TOOL_HIGH_RISK, AGENT_BETA);

        policyRepository.save(new PolicyRule(
                "rule-ks-hr",
                "Approval Rule for High Risk Tool",
                "*",
                TOOL_HIGH_RISK,
                Collections.emptyList(),
                PolicyAction.REQUIRE_APPROVAL,
                false,
                10,
                1,
                true
        ));
        policyRepository.save(new PolicyRule(
                "rule-ks-allow-all",
                "Allow test tools for kill switch tests",
                "*",
                "ks_*",
                Collections.emptyList(),
                PolicyAction.ALLOW,
                false,
                100,
                1,
                true
        ));
        policyEvaluator.reloadPolicies();
    }

    @AfterEach
    void tearDown() {
        killSwitchRepository.deleteAll();
        approvalRepository.deleteAll();
        reset(approvalRepository);
        toolRepository.deleteByName(TOOL_WRITE);
        toolRepository.deleteByName(TOOL_READ);
        toolRepository.deleteByName(TOOL_HIGH_RISK);
        policyRepository.deleteById("rule-ks-hr");
        policyRepository.deleteById("rule-ks-allow-all");
        policyEvaluator.reloadPolicies();
    }

    @Test
    @DisplayName("Agent switch blocks target agent; other agents execute normally")
    void agentKillSwitch_blocksOnlyTargetAgent() {
        // Activate switch for AGENT_ALPHA
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Suspected anomaly", ADMIN_USER);

        ToolExecutionContext ctxAlpha = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_WRITE, Map.of("input", "hello"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult resultAlpha = pipeline.execute(ctxAlpha);

        assertFalse(resultAlpha.success());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, resultAlpha.error());
        assertFalse(resultAlpha.error().contains("agent-alpha"));
        assertFalse(resultAlpha.error().contains("Suspected"));

        // AGENT_BETA must NOT be blocked
        ToolExecutionContext ctxBeta = new ToolExecutionContext(
                AGENT_BETA, TOOL_WRITE, Map.of("input", "hello"), "user-2", RiskLevel.LOW
        );
        ToolExecutionResult resultBeta = pipeline.execute(ctxBeta);

        assertTrue(resultBeta.success());
    }

    @Test
    @DisplayName("Tool switch blocks target tool across all agents; other tools execute normally")
    void toolKillSwitch_blocksTargetToolAcrossAllAgents() {
        // Activate switch for TOOL_WRITE
        killSwitchService.activate(KillSwitchType.TOOL, TOOL_WRITE, "Security advisory CVE-2026-001", ADMIN_USER);

        // AGENT_ALPHA blocked on TOOL_WRITE
        ToolExecutionContext ctxAlphaWrite = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_WRITE, Map.of("input", "test"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult resultAlphaWrite = pipeline.execute(ctxAlphaWrite);
        assertFalse(resultAlphaWrite.success());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, resultAlphaWrite.error());
        assertFalse(resultAlphaWrite.error().contains("TOOL"));
        assertFalse(resultAlphaWrite.error().contains(TOOL_WRITE));
        assertFalse(resultAlphaWrite.error().contains("CVE"));

        // AGENT_BETA also blocked on TOOL_WRITE
        ToolExecutionContext ctxBetaWrite = new ToolExecutionContext(
                AGENT_BETA, TOOL_WRITE, Map.of("input", "test"), "user-2", RiskLevel.LOW
        );
        ToolExecutionResult resultBetaWrite = pipeline.execute(ctxBetaWrite);
        assertFalse(resultBetaWrite.success());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, resultBetaWrite.error());

        // TOOL_READ continues to work for both agents
        ToolExecutionContext ctxAlphaRead = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_READ, Map.of("query", "data"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult resultAlphaRead = pipeline.execute(ctxAlphaRead);
        assertTrue(resultAlphaRead.success());
    }

    @Test
    @DisplayName("GLOBAL_WRITE switch blocks write tools while read tools continue to work")
    void globalWriteKillSwitch_blocksWriteTools_readToolsRemainOperational() {
        // Activate GLOBAL_WRITE switch
        killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, "GLOBAL", "Global write freeze", ADMIN_USER);

        // Mutating write tool (POST) is blocked
        ToolExecutionContext ctxWrite = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_WRITE, Map.of("input", "update"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult resultWrite = pipeline.execute(ctxWrite);
        assertFalse(resultWrite.success());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, resultWrite.error());
        assertFalse(resultWrite.error().contains("GLOBAL_WRITE"));
        assertFalse(resultWrite.error().contains("Global write freeze"));

        // Safe read tool (GET) continues to succeed
        ToolExecutionContext ctxRead = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_READ, Map.of("query", "read-only"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult resultRead = pipeline.execute(ctxRead);
        assertTrue(resultRead.success());
    }

    @Test
    @DisplayName("Ingress check prevents approval request from even being created when kill switch is active")
    void ingressCheck_blocksCreationOfPendingApprovals() {
        // Activate switch for TOOL_HIGH_RISK
        killSwitchService.activate(KillSwitchType.TOOL, TOOL_HIGH_RISK, "High risk tool emergency stop", ADMIN_USER);

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_HIGH_RISK, Map.of("amount", 5000), "user-1", RiskLevel.HIGH
        );

        ToolExecutionResult result = pipeline.execute(ctx);

        assertFalse(result.success());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, result.error());
        assertFalse(result.error().contains("REQUIRE_APPROVAL"), "Should NOT create approval request");

        // Verify zero approval requests created in repository
        List<ApprovalRequest> requests = approvalRepository.findAll().stream()
                .filter(r -> AGENT_ALPHA.equals(r.agentId()))
                .toList();
        assertTrue(requests.isEmpty(), "Approval repository should have no records");
    }

    @Test
    @DisplayName("Approved execution blocked at pre-dispatch check if switch activated after approval")
    void approvedExecution_blockedAtPreDispatch_markedFailed() {
        // 1. Create approval request while kill switch is NOT active
        ApprovalRequest req = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 2000), Duration.ofMinutes(15)
        );
        assertEquals(ApprovalStatus.PENDING, req.status());
        assertEquals(ExecutionState.NOT_DISPATCHED, req.executionState());

        // 2. Transition request to APPROVED directly in repository (controlling dispatch invocation)
        approvalRepository.transitionToApproved(req.id(), null, "officer.smith", Instant.now(), Instant.now());

        // 3. Emergency switch is activated BEFORE execution is dispatched
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Compromised agent incident", ADMIN_USER);

        // Reset spy invocation count
        clearInvocations(restToolAdapter);

        // 4. Background dispatcher attempts execution
        approvedExecutionService.executeApproved(req.id());

        // 5. Downstream adapter must NEVER be called
        verify(restToolAdapter, never()).execute(any(), any());

        // 6. Approval record must be marked FAILED with execution_error set
        ApprovalRequest updated = approvalRepository.findById(req.id()).orElseThrow();
        assertEquals(ExecutionState.FAILED, updated.executionState());
        assertNotNull(updated.executionError());
        assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, updated.executionError());
        assertFalse(updated.executionError().contains("Compromised agent incident"));
    }

    @Test
    @DisplayName("Fail-closed behavior: Database outage blocks execution with KILL_SWITCH_UNAVAILABLE")
    void failClosed_databaseOutage_returnsUnavailableAndBlocks() {
        // Create mock repository that throws on lookup
        KillSwitchRepository mockRepo = mock(KillSwitchRepository.class);
        when(mockRepo.findActiveSwitch(any(), any(), anyBoolean()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection refused"));

        KillSwitchService failClosedService = new KillSwitchService(mockRepo, mock(ApprovalRequestRepository.class));

        KillSwitchCheckResult result = failClosedService.checkBlocked(AGENT_ALPHA, TOOL_WRITE, true);

        assertTrue(result.blocked(), "Must be blocked on database failure");
        assertTrue(result.unavailable(), "Must indicate unavailable");
        assertEquals("KILL_SWITCH_UNAVAILABLE: Emergency controls temporarily unavailable.", result.message());
        assertFalse(result.message().contains("PostgreSQL"), "Must not leak internal database details");
    }

    @Test
    @DisplayName("Idempotent activation and deactivation")
    void idempotentActivationAndDeactivation() {
        // Activate 1
        KillSwitch ks1 = killSwitchService.activate(KillSwitchType.TOOL, TOOL_WRITE, "Initial reason", ADMIN_USER);
        assertTrue(ks1.active());

        // Activate 2 (updates reason)
        KillSwitch ks2 = killSwitchService.activate(KillSwitchType.TOOL, TOOL_WRITE, "Updated reason", "admin.lead");
        assertTrue(ks2.active());
        assertEquals("Updated reason", ks2.reason());
        assertEquals("admin.lead", ks2.createdBy());

        // Deactivate 1
        Optional<KillSwitch> deact1 = killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_WRITE, ADMIN_USER);
        assertTrue(deact1.isPresent());
        assertFalse(deact1.get().active());

        // Deactivate 2 (idempotent)
        Optional<KillSwitch> deact2 = killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_WRITE, ADMIN_USER);
        assertTrue(deact2.isPresent());
        assertFalse(deact2.get().active());
    }

    @Test
    @DisplayName("Direct query persistence: Directly queries database without in-memory caching")
    void directQueryPersistence_authoritativeDirectQuery() {
        // Activate via service
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Audit stop", ADMIN_USER);

        // Verify direct repository query sees it
        Optional<KillSwitch> found = killSwitchRepository.findActiveSwitch(AGENT_ALPHA, "any-tool", false);
        assertTrue(found.isPresent());
        assertEquals("Audit stop", found.get().reason());

        // Deactivate directly in repository
        killSwitchRepository.deactivate(KillSwitchType.AGENT, AGENT_ALPHA, Instant.now());

        // Service check immediately reflects deactivation (no cache stale state)
        KillSwitchCheckResult decision = killSwitchService.checkBlocked(AGENT_ALPHA, "any-tool", false);
        assertFalse(decision.blocked(), "Deactivated switch must immediately allow execution");
    }

    // ==========================================
    // Task 12B: Pending Approval Cancellation Tests
    // ==========================================

    @Test
    @DisplayName("Task 12B: Agent switch cancels only that agent's pending approvals")
    void cancellation_agentSwitchCancelsOnlyTargetAgentPendingApprovals() {
        ApprovalRequest reqAlpha = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 100), Duration.ofMinutes(15)
        );
        ApprovalRequest reqBeta = approvalService.createRequest(
                AGENT_BETA, "user-2", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 200), Duration.ofMinutes(15)
        );

        assertEquals(ApprovalStatus.PENDING, reqAlpha.status());
        assertEquals(ApprovalStatus.PENDING, reqBeta.status());

        // Activate AGENT switch on AGENT_ALPHA
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Rogue agent activity", ADMIN_USER);

        // Verify AGENT_ALPHA pending request transitioned to CANCELLED
        ApprovalRequest updatedAlpha = approvalRepository.findById(reqAlpha.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, updatedAlpha.status());
        assertEquals(ADMIN_USER, updatedAlpha.decidedBy());
        assertNotNull(updatedAlpha.decidedAt());
        assertTrue(updatedAlpha.decisionReason().contains("Rogue agent activity"));

        // Verify AGENT_BETA pending request remains PENDING
        ApprovalRequest updatedBeta = approvalRepository.findById(reqBeta.id()).orElseThrow();
        assertEquals(ApprovalStatus.PENDING, updatedBeta.status());
        assertNull(updatedBeta.decidedBy());
    }

    @Test
    @DisplayName("Task 12B: Tool switch cancels only matching tool approvals")
    void cancellation_toolSwitchCancelsOnlyMatchingToolPendingApprovals() {
        // Register second high risk tool
        String secondTool = "ks_high_risk_tool_2";
        RegisteredTool hr2 = new RegisteredTool(
                "tool-hr2-" + System.nanoTime(),
                secondTool,
                "Second High Risk Tool",
                "{\"type\":\"object\"}",
                RiskLevel.HIGH,
                "Finance",
                "http://localhost:8082/api/test/finance2",
                ProtocolType.REST,
                "POST",
                true,
                Instant.now(),
                Instant.now()
        );
        toolRepository.save(hr2);
        toolRepository.addPermission(secondTool, AGENT_ALPHA);
        policyRepository.save(new PolicyRule(
                "rule-ks-hr-2", "HR2 Rule", "*", secondTool,
                Collections.emptyList(), PolicyAction.REQUIRE_APPROVAL, false, 10, 1, true
        ));
        policyEvaluator.reloadPolicies();

        try {
            ApprovalRequest reqTool1 = approvalService.createRequest(
                    AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                    Map.of("amount", 500), Duration.ofMinutes(15)
            );
            ApprovalRequest reqTool2 = approvalService.createRequest(
                    AGENT_ALPHA, "user-1", secondTool, "rule-ks-hr-2",
                    Map.of("amount", 600), Duration.ofMinutes(15)
            );

            // Activate TOOL switch on TOOL_HIGH_RISK
            killSwitchService.activate(KillSwitchType.TOOL, TOOL_HIGH_RISK, "Tool disabled for audit", ADMIN_USER);

            // TOOL_HIGH_RISK request is CANCELLED
            ApprovalRequest updated1 = approvalRepository.findById(reqTool1.id()).orElseThrow();
            assertEquals(ApprovalStatus.CANCELLED, updated1.status());
            assertEquals(ADMIN_USER, updated1.decidedBy());

            // secondTool request remains PENDING
            ApprovalRequest updated2 = approvalRepository.findById(reqTool2.id()).orElseThrow();
            assertEquals(ApprovalStatus.PENDING, updated2.status());
        } finally {
            toolRepository.deleteByName(secondTool);
            policyRepository.deleteById("rule-ks-hr-2");
            policyEvaluator.reloadPolicies();
        }
    }

    @Test
    @DisplayName("Task 12B: Global write switch cancels write approvals while preserving read-only approvals")
    void cancellation_globalWriteSwitchCancelsWriteApprovalsAndPreservesRead() {
        // Add approval policy for read tool to allow creating pending approval for read tool
        policyRepository.save(new PolicyRule(
                "rule-ks-read-hr", "Read HR Rule", "*", TOOL_READ,
                Collections.emptyList(), PolicyAction.REQUIRE_APPROVAL, false, 5, 1, true
        ));
        policyEvaluator.reloadPolicies();

        try {
            // Write request (POST)
            ApprovalRequest writeReq = approvalService.createRequest(
                    AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                    Map.of("amount", 300), Duration.ofMinutes(15)
            );
            // Read request (GET)
            ApprovalRequest readReq = approvalService.createRequest(
                    AGENT_ALPHA, "user-1", TOOL_READ, "rule-ks-read-hr",
                    Map.of("query", "data"), Duration.ofMinutes(15)
            );

            // Activate GLOBAL_WRITE switch
            killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, "GLOBAL", "Global write freeze", ADMIN_USER);

            // Write approval is CANCELLED
            ApprovalRequest updatedWrite = approvalRepository.findById(writeReq.id()).orElseThrow();
            assertEquals(ApprovalStatus.CANCELLED, updatedWrite.status());
            assertEquals(ADMIN_USER, updatedWrite.decidedBy());

            // Read approval remains PENDING
            ApprovalRequest updatedRead = approvalRepository.findById(readReq.id()).orElseThrow();
            assertEquals(ApprovalStatus.PENDING, updatedRead.status());
        } finally {
            policyRepository.deleteById("rule-ks-read-hr");
            policyEvaluator.reloadPolicies();
        }
    }

    @Test
    @DisplayName("Task 12B: Rejected, expired and already cancelled requests are not incorrectly transitioned")
    void cancellation_doesNotTransitionRejectedExpiredOrCancelled() {
        // 1. REJECTED request
        ApprovalRequest req1 = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 10), Duration.ofMinutes(15)
        );
        approvalService.reject(req1.id(), "officer.reject", "Denied");
        ApprovalRequest rejected = approvalRepository.findById(req1.id()).orElseThrow();
        assertEquals(ApprovalStatus.REJECTED, rejected.status());
        Instant decidedAtOrig = rejected.decidedAt();

        // 2. EXPIRED request
        ApprovalRequest req2 = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 20), Duration.ofMinutes(15)
        );
        approvalRepository.transitionToExpired(req2.id(), Instant.now().plus(Duration.ofMinutes(20)));
        ApprovalRequest expired = approvalRepository.findById(req2.id()).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, expired.status());

        // 3. Already CANCELLED request
        ApprovalRequest req3 = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 30), Duration.ofMinutes(15)
        );
        approvalRepository.transitionToCancelled(req3.id(), "user.self", "User abort", Instant.now());
        ApprovalRequest cancelled = approvalRepository.findById(req3.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, cancelled.status());
        assertEquals("user.self", cancelled.decidedBy());

        // Activate switch
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Kill all", ADMIN_USER);

        // Verify states, timestamps and attribution remain untouched
        ApprovalRequest afterRejected = approvalRepository.findById(req1.id()).orElseThrow();
        assertEquals(ApprovalStatus.REJECTED, afterRejected.status());
        assertEquals("officer.reject", afterRejected.decidedBy());
        assertEquals(decidedAtOrig.getEpochSecond(), afterRejected.decidedAt().getEpochSecond());

        ApprovalRequest afterExpired = approvalRepository.findById(req2.id()).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, afterExpired.status());

        ApprovalRequest afterCancelled = approvalRepository.findById(req3.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, afterCancelled.status());
        assertEquals("user.self", afterCancelled.decidedBy());
    }

    @Test
    @DisplayName("Task 12B: CANCELLED requests cannot be approved afterward")
    void cancellation_cancelledRequestsCannotBeApprovedAfterward() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 999), Duration.ofMinutes(15)
        );

        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Emergency stop", ADMIN_USER);

        ApprovalRequest cancelled = approvalRepository.findById(req.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, cancelled.status());

        // Attempting to approve must fail with InvalidApprovalStateException
        InvalidApprovalStateException ex = assertThrows(InvalidApprovalStateException.class, () ->
                approvalService.approve(req.id(), "officer.smith", null)
        );
        assertTrue(ex.getMessage().contains("CANCELLED"));

        // Status remains CANCELLED
        ApprovalRequest stillCancelled = approvalRepository.findById(req.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, stillCancelled.status());
    }

    @Test
    @DisplayName("Task 12B: Deactivation never revives cancelled requests")
    void cancellation_deactivationNeverRevivesCancelledRequests() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 444), Duration.ofMinutes(15)
        );

        // Activate switch -> cancels pending
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Emergency stop", ADMIN_USER);
        assertEquals(ApprovalStatus.CANCELLED, approvalRepository.findById(req.id()).orElseThrow().status());

        // Deactivate switch
        killSwitchService.deactivate(KillSwitchType.AGENT, AGENT_ALPHA, ADMIN_USER);

        // Request MUST still be CANCELLED
        ApprovalRequest afterDeact = approvalRepository.findById(req.id()).orElseThrow();
        assertEquals(ApprovalStatus.CANCELLED, afterDeact.status());

        // Attempting to approve still fails
        assertThrows(InvalidApprovalStateException.class, () ->
                approvalService.approve(req.id(), "officer.smith", null)
        );
    }

    @Test
    @DisplayName("Task 12B: get_approval_result returns CANCELLED and safe generic message without leaking internals")
    void getApprovalResult_forCancelledRequest_returnsCancelledStatusAndGenericMessage() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 777), Duration.ofMinutes(15)
        );

        killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "TOP SECRET INTERNAL REASON 123", ADMIN_USER);

        Optional<ApprovalResultResponse> resultOpt = approvalService.getApprovalResult(req.id(), AGENT_ALPHA);
        assertTrue(resultOpt.isPresent());

        ApprovalResultResponse res = resultOpt.get();
        assertEquals("CANCELLED", res.status());
        assertEquals("Approval request was cancelled", res.message());
        assertNull(res.error());
        assertNull(res.result());
        assertFalse(res.message().contains("TOP SECRET"), "Must NOT disclose administrative reason");
    }

    @Test
    @DisplayName("Task 12B: Cancellation failure rolls back the activation transaction")
    void cancellation_rollbackOnFailure() {
        doThrow(new RuntimeException("Simulated database failure during cancellation"))
                .when(approvalRepository).cancelPendingByAgent(eq("agent-test-rollback"), any(), any(), any());

        // When activation is attempted, the runtime exception must propagate
        assertThrows(RuntimeException.class, () ->
                killSwitchService.activate(KillSwitchType.AGENT, "agent-test-rollback", "Fail reason", ADMIN_USER)
        );

        // Switch must NOT be active or persisted in database due to transaction rollback
        Optional<KillSwitch> found = killSwitchRepository.findByTypeAndTarget(KillSwitchType.AGENT, "agent-test-rollback");
        assertTrue(found.isEmpty(), "Kill switch activation must be rolled back on cancellation failure");
    }

    @Test
    @DisplayName("Task 12B: Concurrent approval and activation behave safely")
    void concurrency_concurrentApprovalAndActivationBehaveSafely() throws Exception {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                Map.of("amount", 888), Duration.ofMinutes(15)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<?> f1 = executor.submit(() -> {
            try {
                barrier.await();
                approvalService.approve(req.id(), "officer.smith", null);
            } catch (InvalidApprovalStateException e) {
                // Expected if switch activation won the race
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                barrier.await();
                killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Concurrent stop", ADMIN_USER);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Downstream adapter must NEVER be executed
        verify(restToolAdapter, never()).execute(any(), any());

        // If status became APPROVED, then any dispatch attempt must fail at pre-dispatch
        ApprovalRequest finalReq = approvalRepository.findById(req.id()).orElseThrow();
        if (finalReq.status() == ApprovalStatus.APPROVED) {
            // Wait for background execution to complete governance recheck
            for (int i = 0; i < 30; i++) {
                ApprovalRequest current = approvalRepository.findById(req.id()).orElseThrow();
                if (current.executionState() != ExecutionState.NOT_DISPATCHED
                        && current.executionState() != ExecutionState.DISPATCHED) {
                    break;
                }
                Thread.sleep(100);
            }
            ApprovalRequest afterExec = approvalRepository.findById(req.id()).orElseThrow();
            assertEquals(ExecutionState.FAILED, afterExec.executionState());
            assertEquals(KillSwitchCheckResult.GENERIC_BLOCKED_MESSAGE, afterExec.executionError());
        } else {
            assertEquals(ApprovalStatus.CANCELLED, finalReq.status());
        }
    }

    @Test
    @DisplayName("Task 12B Concurrency: Approval creation overlapping switch activation never leaves actionable PENDING request")
    void concurrency_approvalCreationOverlappingSwitchActivation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        // Thread 1: attempts to create a high-risk approval request
        Future<?> f1 = executor.submit(() -> {
            try {
                barrier.await();
                approvalService.createRequest(
                        AGENT_ALPHA, "user-concurrent-1", TOOL_HIGH_RISK, "rule-ks-hr",
                        Map.of("amount", 9500), Duration.ofMinutes(15)
                );
            } catch (IllegalStateException e) {
                // Expected if switch activation locked table or committed first:
                // "KILL_SWITCH_ACTIVE: Tool execution is disabled by an emergency control."
                assertTrue(e.getMessage().contains("KILL_SWITCH_ACTIVE"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: activates emergency kill switch for TOOL_HIGH_RISK
        Future<?> f2 = executor.submit(() -> {
            try {
                barrier.await();
                killSwitchService.activate(KillSwitchType.TOOL, TOOL_HIGH_RISK, "Emergency incident #771", ADMIN_USER);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant: ZERO actionable PENDING requests for TOOL_HIGH_RISK may exist
        List<ApprovalRequest> pendingRequests = approvalRepository.findAll().stream()
                .filter(r -> TOOL_HIGH_RISK.equals(r.toolName()) && r.status() == ApprovalStatus.PENDING)
                .toList();
        assertTrue(pendingRequests.isEmpty(), "No actionable PENDING approval request may exist after switch activation!");

        // Any created request must have been transitioned to CANCELLED
        List<ApprovalRequest> allRequests = approvalRepository.findAll().stream()
                .filter(r -> TOOL_HIGH_RISK.equals(r.toolName()))
                .toList();
        for (ApprovalRequest r : allRequests) {
            assertEquals(ApprovalStatus.CANCELLED, r.status());
            assertEquals(ADMIN_USER, r.decidedBy());
        }
    }

    @Test
    @DisplayName("Task 12B Privacy: Agent response never exposes internal incident reason, admin username or ticket notes")
    void genericError_neverExposesInternalReasonOrCredentials() {
        String sensitiveReason = "INVESTIGATION INCIDENT #9981: Confidential breach investigation by admin.sec";
        killSwitchService.activate(KillSwitchType.TOOL, TOOL_WRITE, sensitiveReason, "admin.sec");

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_ALPHA, TOOL_WRITE, Map.of("input", "test"), "user-1", RiskLevel.LOW
        );
        ToolExecutionResult result = pipeline.execute(ctx);

        assertFalse(result.success());
        assertEquals("KILL_SWITCH_ACTIVE: Tool execution is disabled by an emergency control.", result.error());
        assertFalse(result.error().contains("INVESTIGATION"));
        assertFalse(result.error().contains("9981"));
        assertFalse(result.error().contains("Confidential"));
        assertFalse(result.error().contains("admin.sec"));
        assertFalse(result.error().contains("TOOL"));
        assertFalse(result.error().contains(TOOL_WRITE));
    }

    @Test
    @DisplayName("GLOBAL_WRITE switch preserves read-only approvals while blocking write approvals and honoring specific switches")
    void globalWriteSwitch_preservesReadOnlyApprovals_whileBlockingWriteApprovalsAndSpecificSwitches() {
        // Register approval policy for read tool
        policyRepository.save(new PolicyRule(
                "rule-ks-read-hr", "Read HR Rule", "*", TOOL_READ,
                Collections.emptyList(), PolicyAction.REQUIRE_APPROVAL, false, 5, 1, true
        ));
        policyEvaluator.reloadPolicies();

        try {
            // Activate GLOBAL_WRITE switch
            killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, "GLOBAL", "Global write freeze", ADMIN_USER);

            // 1. A write tool cannot create a new approval
            IllegalStateException writeEx = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () ->
                            approvalService.createRequest(
                                    AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                                    Map.of("amount", 500), java.time.Duration.ofMinutes(15)
                            )
            );
            assertTrue(writeEx.getMessage().contains("KILL_SWITCH_ACTIVE"));

            // 2. An existing write approval cannot transition to APPROVED
            ApprovalRequest preExistingWrite = new ApprovalRequest(
                    "appr-pre-write-1", AGENT_ALPHA, "user-1", TOOL_HIGH_RISK, "rule-ks-hr",
                    "{\"amount\":100}", null, ApprovalStatus.PENDING,
                    java.time.Instant.now(), java.time.Instant.now().plus(java.time.Duration.ofMinutes(15)), null, null, null
            );
            // Insert directly to test repository update query isolation
            jdbcClient.sql("""
                    INSERT INTO approval_requests (id, agent_id, user_id, tool_name, policy_rule_id,
                                                   original_arguments_json, status, created_at, expires_at)
                    VALUES (:id, :agentId, :userId, :toolName, :policyRuleId, :origArgs, :status, :createdAt, :expiresAt)
                    """)
                    .param("id", preExistingWrite.id())
                    .param("agentId", preExistingWrite.agentId())
                    .param("userId", preExistingWrite.userId())
                    .param("toolName", preExistingWrite.toolName())
                    .param("policyRuleId", preExistingWrite.policyRuleId())
                    .param("origArgs", preExistingWrite.originalArgumentsJson())
                    .param("status", ApprovalStatus.PENDING.name())
                    .param("createdAt", java.sql.Timestamp.from(preExistingWrite.createdAt()))
                    .param("expiresAt", java.sql.Timestamp.from(preExistingWrite.expiresAt()))
                    .update();

            int updatedWrite = approvalRepository.transitionToApproved(
                    preExistingWrite.id(), null, "approver.1", java.time.Instant.now(), java.time.Instant.now()
            );
            assertEquals(0, updatedWrite, "Existing write approval must NOT transition to APPROVED when GLOBAL_WRITE is active!");

            // 3. A read-only tool requiring approval can still create a PENDING request
            ApprovalRequest readReq = approvalService.createRequest(
                    AGENT_ALPHA, "user-1", TOOL_READ, "rule-ks-read-hr",
                    Map.of("query", "select * from audit"), java.time.Duration.ofMinutes(15)
            );
            assertNotNull(readReq);
            assertEquals(ApprovalStatus.PENDING, readReq.status());
            assertEquals(TOOL_READ, readReq.toolName());

            // 4. Its human approval can still proceed
            ApprovalRequest approvedRead = approvalService.approve(readReq.id(), "approver.1", null);
            assertNotNull(approvedRead);
            assertEquals(ApprovalStatus.APPROVED, approvedRead.status());
            assertEquals("approver.1", approvedRead.decidedBy());

            // 5. A tool-specific or agent-specific kill switch still blocks applicable read-only requests
            // 5a. Tool-specific kill switch on read tool
            killSwitchService.activate(KillSwitchType.TOOL, TOOL_READ, "Read tool emergency maintenance", ADMIN_USER);

            IllegalStateException toolBlockedEx = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () ->
                            approvalService.createRequest(
                                    AGENT_ALPHA, "user-1", TOOL_READ, "rule-ks-read-hr",
                                    Map.of("query", "test"), java.time.Duration.ofMinutes(15)
                            )
            );
            assertTrue(toolBlockedEx.getMessage().contains("KILL_SWITCH_ACTIVE"));

            // Deactivate tool switch to isolate agent switch test
            killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_READ, ADMIN_USER);

            // 5b. Agent-specific kill switch on AGENT_ALPHA
            killSwitchService.activate(KillSwitchType.AGENT, AGENT_ALPHA, "Compromised agent alpha", ADMIN_USER);

            IllegalStateException agentBlockedEx = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () ->
                            approvalService.createRequest(
                                    AGENT_ALPHA, "user-1", TOOL_READ, "rule-ks-read-hr",
                                    Map.of("query", "test2"), java.time.Duration.ofMinutes(15)
                            )
            );
            assertTrue(agentBlockedEx.getMessage().contains("KILL_SWITCH_ACTIVE"));

        } finally {
            policyRepository.deleteById("rule-ks-read-hr");
            policyEvaluator.reloadPolicies();
        }
    }
}
