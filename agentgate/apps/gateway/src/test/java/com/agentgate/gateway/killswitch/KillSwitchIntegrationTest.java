package com.agentgate.gateway.killswitch;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private StructuredPolicyEvaluator policyEvaluator;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    private static final String AGENT_ALPHA = "agent-alpha";
    private static final String AGENT_BETA = "agent-beta";

    private static final String TOOL_WRITE = "ks_write_tool";
    private static final String TOOL_READ = "ks_read_tool";
    private static final String TOOL_HIGH_RISK = "ks_high_risk_tool";

    private static final String ADMIN_USER = "admin.security";

    @BeforeEach
    void setUp() {
        killSwitchRepository.deleteAll();

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
        assertTrue(resultAlpha.error().startsWith("KILL_SWITCH_ACTIVE"), "Error should start with KILL_SWITCH_ACTIVE");
        assertTrue(resultAlpha.error().contains("agent-alpha"));

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
        assertTrue(resultAlphaWrite.error().startsWith("KILL_SWITCH_ACTIVE"));
        assertTrue(resultAlphaWrite.error().contains("TOOL"));
        assertTrue(resultAlphaWrite.error().contains(TOOL_WRITE));

        // AGENT_BETA also blocked on TOOL_WRITE
        ToolExecutionContext ctxBetaWrite = new ToolExecutionContext(
                AGENT_BETA, TOOL_WRITE, Map.of("input", "test"), "user-2", RiskLevel.LOW
        );
        ToolExecutionResult resultBetaWrite = pipeline.execute(ctxBetaWrite);
        assertFalse(resultBetaWrite.success());
        assertTrue(resultBetaWrite.error().startsWith("KILL_SWITCH_ACTIVE"));

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
        assertTrue(resultWrite.error().startsWith("KILL_SWITCH_ACTIVE"));
        assertTrue(resultWrite.error().contains("GLOBAL_WRITE"));

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
        assertTrue(result.error().startsWith("KILL_SWITCH_ACTIVE"));
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
        assertTrue(updated.executionError().startsWith("KILL_SWITCH_ACTIVE"));
        assertTrue(updated.executionError().contains("Compromised agent incident"));
    }

    @Test
    @DisplayName("Fail-closed behavior: Database outage blocks execution with KILL_SWITCH_UNAVAILABLE")
    void failClosed_databaseOutage_returnsUnavailableAndBlocks() {
        // Create mock repository that throws on lookup
        KillSwitchRepository mockRepo = mock(KillSwitchRepository.class);
        when(mockRepo.findActiveSwitch(any(), any(), anyBoolean()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection refused"));

        KillSwitchService failClosedService = new KillSwitchService(mockRepo);

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
}
