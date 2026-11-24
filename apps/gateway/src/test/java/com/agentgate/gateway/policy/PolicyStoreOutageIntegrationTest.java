package com.agentgate.gateway.policy;

import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditVerificationResult;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.audit.service.AuditVerificationService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.repository.KillSwitchRepository;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.ConditionOperator;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.domain.StructuredCondition;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.ratelimit.RateLimitDecision;
import com.agentgate.gateway.ratelimit.RateLimiter;
import com.agentgate.gateway.ratelimit.WriteCapDecision;
import com.agentgate.gateway.ratelimit.WriteCapLimiter;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Focused integration verification for QA4 Policy-Store Outage Semantics.
 * Exercises the real ToolExecutionPipeline, StructuredPolicyEvaluator, KillSwitchService,
 * RegisteredToolRepository, and synchronous H2-backed AuditRepository while injecting
 * a real DataAccessResourceFailureException at PolicyRepository.findAll().
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, PolicyStoreOutageIntegrationTest.TestClockConfig.class})
@DisplayName("QA4 Policy-Store Outage Integration Verification")
class PolicyStoreOutageIntegrationTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        MutableClock testPolicyClock() {
            return new MutableClock(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.current = initial;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.current = instant;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private StructuredPolicyEvaluator evaluator;

    @MockitoSpyBean
    private PolicyRepository policyRepository;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private KillSwitchRepository killSwitchRepository;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditVerificationService auditVerificationService;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    @MockitoSpyBean
    private RateLimiter rateLimiter;

    @MockitoSpyBean
    private WriteCapLimiter writeCapLimiter;

    private static final String AGENT_ID = "agent-qa4-tester";
    private static final String USER_ID = "cust-qa4-101";

    private static final String TOOL_LOW = "qa4_low_read";
    private static final String TOOL_LOW_UNMATCHED = "qa4_low_unmatched";
    private static final String TOOL_MEDIUM = "qa4_medium_write";
    private static final String TOOL_HIGH = "qa4_high_refund";

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock.setInstant(T0);
        reset(policyRepository, restToolAdapter, rateLimiter, writeCapLimiter);

        auditRepository.resetToGenesis();
        killSwitchRepository.deleteAll();

        doReturn(ToolExecutionResult.success(Map.of("status", "ok"), 12L))
                .when(restToolAdapter).execute(any(), any());
        doReturn(RateLimitDecision.allow()).when(rateLimiter).tryAcquire(any(), any());
        doReturn(WriteCapDecision.allow()).when(writeCapLimiter).tryAcquireWrite(any());

        registerTool(TOOL_LOW, RiskLevel.LOW, "GET");
        registerTool(TOOL_LOW_UNMATCHED, RiskLevel.LOW, "GET");
        registerTool(TOOL_MEDIUM, RiskLevel.MEDIUM, "POST");
        registerTool(TOOL_HIGH, RiskLevel.HIGH, "POST");

        policyRepository.save(new PolicyRule(
                "pol-qa4-low", "QA4 Allow Low Read", AGENT_ID, TOOL_LOW,
                List.of(new StructuredCondition("customerId", ConditionOperator.EQUALS_USER_ID, null)),
                PolicyAction.ALLOW, false, 10, 1, true
        ));
        policyRepository.save(new PolicyRule(
                "pol-qa4-med", "QA4 Allow Medium Write", AGENT_ID, TOOL_MEDIUM,
                List.of(), PolicyAction.ALLOW, false, 20, 1, true
        ));
        policyRepository.save(new PolicyRule(
                "pol-qa4-high", "QA4 Allow High Refund", AGENT_ID, TOOL_HIGH,
                List.of(new StructuredCondition("amount", ConditionOperator.LTE, 500)),
                PolicyAction.ALLOW, false, 30, 1, true
        ));

        evaluator.reloadPolicies();
    }

    private void registerTool(String name, RiskLevel risk, String method) {
        toolRepository.save(new RegisteredTool(
                "id-" + name, name, "QA4 test tool " + name,
                "{\"type\":\"object\"}", risk, "qa4-team",
                "http://localhost:8082/api/" + name, ProtocolType.REST,
                method, true, T0, T0
        ));
        toolRepository.addPermission(name, AGENT_ID);
    }

    @Test
    @DisplayName("QA4 Lifecycle: Healthy -> Policy Outage (Med/High Fail Closed, Low <5m Cached, >=5m Cutoff) -> Recovery & Audit Integrity")
    void fullQa4OutageLifecycle_enforcesFailSafeAndPreservesAuditChain() {
        // A. Healthy baseline
        ToolExecutionResult lowHealthy = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertTrue(lowHealthy.success(), "Healthy low-risk call should succeed");

        ToolExecutionResult medHealthy = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_MEDIUM, Map.of("note", "healthy"), USER_ID, RiskLevel.MEDIUM));
        assertTrue(medHealthy.success(), "Healthy medium-risk call should succeed");

        ToolExecutionResult highHealthy = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_HIGH, Map.of("amount", 100), USER_ID, RiskLevel.HIGH));
        assertTrue(highHealthy.success(), "Healthy high-risk call should succeed");

        verify(restToolAdapter, times(3)).execute(any(), any());
        clearInvocations(restToolAdapter);

        // B. Inject real PolicyRepository.findAll() failure and invoke reloadPolicies()
        doThrow(new DataAccessResourceFailureException("Simulated policy store unreachable"))
                .when(policyRepository).findAll();
        evaluator.reloadPolicies();

        // C. Medium and High risk calls MUST fail closed immediately without downstream execution
        clock.setInstant(T0.plusSeconds(30));
        ToolExecutionResult medOutage = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_MEDIUM, Map.of("note", "during-outage"), USER_ID, RiskLevel.MEDIUM));
        assertFalse(medOutage.success());
        assertThat(medOutage.error()).contains("POLICY_DENIED").contains("medium/high risk calls fail closed");

        ToolExecutionResult highOutage = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_HIGH, Map.of("amount", 100), USER_ID, RiskLevel.HIGH));
        assertFalse(highOutage.success());
        assertThat(highOutage.error()).contains("POLICY_DENIED").contains("medium/high risk calls fail closed");

        verify(restToolAdapter, never()).execute(any(), any());

        // D. Low-risk behavior within 5 minutes: matched rule executes, unmatched default-denied, kill switch enforced
        ToolExecutionResult lowOutageAllowed = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertTrue(lowOutageAllowed.success(), "Matched low-risk call should succeed on cached policy within 5m");
        verify(restToolAdapter, times(1)).execute(any(), any());
        clearInvocations(restToolAdapter);

        ToolExecutionResult lowUnmatchedDenied = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW_UNMATCHED, Map.of(), USER_ID, RiskLevel.LOW));
        assertFalse(lowUnmatchedDenied.success(), "Unmatched low-risk tool must remain default-denied during outage");
        assertThat(lowUnmatchedDenied.error()).contains("Default Deny");
        verify(restToolAdapter, never()).execute(any(), any());

        // Verify kill switch remains enforced for low-risk tools even during policy outage
        killSwitchService.activate(KillSwitchType.TOOL, TOOL_LOW, "QA4 outage kill test", "admin-qa4");
        ToolExecutionResult lowKilled = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertFalse(lowKilled.success());
        assertThat(lowKilled.error()).contains("KILL_SWITCH");
        killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_LOW, "admin-qa4");
        verify(restToolAdapter, never()).execute(any(), any());

        // E. Exact 5-minute expiry boundary
        // E1: 4m 59s (299s) -> still permitted on cached policy
        clock.setInstant(T0.plusSeconds(299));
        ToolExecutionResult at4m59s = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertTrue(at4m59s.success(), "Low-risk call at 4m59s (< 5m) should still succeed");
        verify(restToolAdapter, times(1)).execute(any(), any());
        clearInvocations(restToolAdapter);

        // E2: Exactly 5m 00s (300s) -> expired cutoff, fail closed
        clock.setInstant(T0.plusSeconds(300));
        ToolExecutionResult at5m00s = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertFalse(at5m00s.success(), "Low-risk call at exactly 5m00s must fail closed");
        assertThat(at5m00s.error()).contains("low-risk fallback window expired");
        verify(restToolAdapter, never()).execute(any(), any());

        // E3: 5m 01s (301s) -> still expired cutoff, fail closed
        clock.setInstant(T0.plusSeconds(301));
        ToolExecutionResult at5m01s = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertFalse(at5m01s.success(), "Low-risk call at 5m01s must fail closed");
        assertThat(at5m01s.error()).contains("low-risk fallback window expired");
        verify(restToolAdapter, never()).execute(any(), any());

        // F. Recovery: restore repository, update policy to DENY, reload, and verify new state takes effect
        reset(policyRepository);
        policyRepository.save(new PolicyRule(
                "pol-qa4-low", "QA4 Revoked Low Read", AGENT_ID, TOOL_LOW,
                List.of(), PolicyAction.DENY, false, 10, 2, true
        ));
        clock.setInstant(T0.plusSeconds(310));
        evaluator.reloadPolicies();

        ToolExecutionResult afterRecoveryRevoked = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertFalse(afterRecoveryRevoked.success(), "Newly persisted DENY policy must override previously cached ALLOW after recovery");
        assertThat(afterRecoveryRevoked.error()).contains("POLICY_DENIED");
        verify(restToolAdapter, never()).execute(any(), any());

        // Medium-risk call succeeds again after recovery
        ToolExecutionResult medRecovered = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_MEDIUM, Map.of("note", "recovered"), USER_ID, RiskLevel.MEDIUM));
        assertTrue(medRecovered.success(), "Medium-risk call should succeed again after policy store recovery");
        verify(restToolAdapter, times(1)).execute(any(), any());

        // G. Audit integrity verification across all outage allow/deny/kill-switch/recovery events
        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertThat(events).hasSize(16);
        assertThat(events).anyMatch(e -> "QA4_FAIL_CLOSED".equals(e.policyRuleId()) && "BLOCKED".equals(e.outcome()));
        assertThat(events).anyMatch(e -> "QA4_CUTOFF".equals(e.policyRuleId()) && "BLOCKED".equals(e.outcome()));
        assertThat(events).anyMatch(e -> "pol-qa4-low".equals(e.policyRuleId()) && "SUCCESS".equals(e.outcome()));
        assertThat(events).anyMatch(e -> "KILL_SWITCH_BLOCKED".equals(e.decision()) && "BLOCKED".equals(e.outcome()));

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid(), "SHA-256 audit chain must remain valid across all QA4 outage events");
        assertNull(verification.corruptedSequence());
        assertEquals(events.size(), verification.recordsChecked());
    }
}
