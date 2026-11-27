package com.agentgate.gateway.policy;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Clock;
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
 * QA4 integration test against a disposable PostgreSQL Testcontainer with Flyway migrations.
 * Reproduces a real PostgreSQL policy-read failure (renaming the {@code policies} table)
 * using the unmocked {@link PolicyRepository} while registry, kill-switch, and audit tables
 * remain reachable in PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, PolicyStoreOutagePostgresIT.TestClockConfig.class})
@DisplayName("QA4 Real-PostgreSQL Policy-Read Outage Integration Test")
class PolicyStoreOutagePostgresIT {

    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", "agentgate_qa4")
            .withEnv("POSTGRES_USER", "agentgate")
            .withEnv("POSTGRES_PASSWORD", "fixture")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/agentgate_qa4",
                        "agentgate",
                        "fixture"
                )
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/agentgate_qa4");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "agentgate");
        registry.add("spring.datasource.password", () -> "fixture");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        MutableClock testPolicyClock() {
            return new MutableClock(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC);
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private StructuredPolicyEvaluator evaluator;

    @Autowired
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

    private static final String AGENT_ID = "agent-qa4-pg";
    private static final String USER_ID = "cust-qa4-pg-101";

    private static final String TOOL_LOW = "qa4_pg_low_read";
    private static final String TOOL_LOW_UNMATCHED = "qa4_pg_low_unmatched";
    private static final String TOOL_MEDIUM = "qa4_pg_medium_write";
    private static final String TOOL_HIGH = "qa4_pg_high_refund";

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock.setInstant(T0);
        reset(restToolAdapter, rateLimiter, writeCapLimiter);

        jdbcTemplate.execute("ALTER TABLE IF EXISTS policies_outage RENAME TO policies");
        auditRepository.resetToGenesis();
        killSwitchRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM policies");

        doReturn(ToolExecutionResult.success(Map.of("status", "ok"), 12L))
                .when(restToolAdapter).execute(any(), any());
        doReturn(RateLimitDecision.allow()).when(rateLimiter).tryAcquire(any(), any());
        doReturn(WriteCapDecision.allow()).when(writeCapLimiter).tryAcquireWrite(any());

        registerTool(TOOL_LOW, RiskLevel.LOW, "GET");
        registerTool(TOOL_LOW_UNMATCHED, RiskLevel.LOW, "GET");
        registerTool(TOOL_MEDIUM, RiskLevel.MEDIUM, "POST");
        registerTool(TOOL_HIGH, RiskLevel.HIGH, "POST");

        policyRepository.save(new PolicyRule(
                "pol-qa4-pg-low", "QA4 PG Allow Low Read", AGENT_ID, TOOL_LOW,
                List.of(new StructuredCondition("customerId", ConditionOperator.EQUALS_USER_ID, null)),
                PolicyAction.ALLOW, false, 10, 1, true
        ));
        policyRepository.save(new PolicyRule(
                "pol-qa4-pg-med", "QA4 PG Allow Medium Write", AGENT_ID, TOOL_MEDIUM,
                List.of(), PolicyAction.ALLOW, false, 20, 1, true
        ));
        policyRepository.save(new PolicyRule(
                "pol-qa4-pg-high", "QA4 PG Allow High Refund", AGENT_ID, TOOL_HIGH,
                List.of(new StructuredCondition("amount", ConditionOperator.LTE, 500)),
                PolicyAction.ALLOW, false, 30, 1, true
        ));

        evaluator.reloadPolicies();
    }

    private void registerTool(String name, RiskLevel risk, String method) {
        toolRepository.save(new RegisteredTool(
                "id-" + name, name, "QA4 PG test tool " + name,
                "{\"type\":\"object\"}", risk, "qa4-team",
                "http://localhost:8082/api/" + name, ProtocolType.REST,
                method, true, T0, T0
        ));
        toolRepository.addPermission(name, AGENT_ID);
    }

    @Test
    @DisplayName("Real PostgreSQL policy-read failure: med/high fail closed, low <5m cached, >=5m cutoff, kill switch & audit active")
    void realPostgresPolicyReadFailure_enforcesFailSafeWhileAuditAndKillSwitchWork() {
        // A. Healthy baseline
        assertTrue(pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW)).success());
        assertTrue(pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_MEDIUM, Map.of("note", "healthy"), USER_ID, RiskLevel.MEDIUM)).success());
        assertTrue(pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_HIGH, Map.of("amount", 100), USER_ID, RiskLevel.HIGH)).success());

        verify(restToolAdapter, times(3)).execute(any(), any());
        clearInvocations(restToolAdapter);

        // B. Break real PostgreSQL policy reads while registry, kill_switches, and audit_log stay reachable
        jdbcTemplate.execute("ALTER TABLE policies RENAME TO policies_outage");
        try {
            evaluator.reloadPolicies();

            // C. Medium and high risk fail closed before dispatch
            clock.setInstant(T0.plusSeconds(30));
            ToolExecutionResult medOutage = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_MEDIUM, Map.of("note", "outage"), USER_ID, RiskLevel.MEDIUM));
            assertFalse(medOutage.success());
            assertThat(medOutage.error()).contains("POLICY_DENIED").contains("medium/high risk calls fail closed");

            ToolExecutionResult highOutage = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_HIGH, Map.of("amount", 100), USER_ID, RiskLevel.HIGH));
            assertFalse(highOutage.success());
            assertThat(highOutage.error()).contains("POLICY_DENIED").contains("medium/high risk calls fail closed");
            verify(restToolAdapter, never()).execute(any(), any());

            // D. Matching low-risk succeeds on cached policy < 5m; unmatched default-denied; kill switch works
            ToolExecutionResult lowOutage = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
            assertTrue(lowOutage.success());
            verify(restToolAdapter, times(1)).execute(any(), any());
            clearInvocations(restToolAdapter);

            ToolExecutionResult lowUnmatched = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW_UNMATCHED, Map.of(), USER_ID, RiskLevel.LOW));
            assertFalse(lowUnmatched.success());
            assertThat(lowUnmatched.error()).contains("Default Deny");

            killSwitchService.activate(KillSwitchType.TOOL, TOOL_LOW, "QA4 PG kill test", "admin-qa4");
            ToolExecutionResult lowKilled = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
            assertFalse(lowKilled.success());
            assertThat(lowKilled.error()).contains("KILL_SWITCH");
            killSwitchService.deactivate(KillSwitchType.TOOL, TOOL_LOW, "admin-qa4");
            verify(restToolAdapter, never()).execute(any(), any());

            // E. Exact 5-minute cutoff: 299s allowed, 300s & 301s blocked
            clock.setInstant(T0.plusSeconds(299));
            assertTrue(pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW)).success());
            verify(restToolAdapter, times(1)).execute(any(), any());
            clearInvocations(restToolAdapter);

            clock.setInstant(T0.plusSeconds(300));
            ToolExecutionResult at300s = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
            assertFalse(at300s.success());
            assertThat(at300s.error()).contains("low-risk fallback window expired");

            clock.setInstant(T0.plusSeconds(301));
            ToolExecutionResult at301s = pipeline.execute(new ToolExecutionContext(
                    AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
            assertFalse(at301s.success());
            assertThat(at301s.error()).contains("low-risk fallback window expired");
            verify(restToolAdapter, never()).execute(any(), any());
        } finally {
            // F. Restore policies table
            jdbcTemplate.execute("ALTER TABLE policies_outage RENAME TO policies");
        }

        // G. Recovery & real PostgreSQL audit chain integrity
        policyRepository.save(new PolicyRule(
                "pol-qa4-pg-low", "QA4 PG Revoked Low Read", AGENT_ID, TOOL_LOW,
                List.of(), PolicyAction.DENY, false, 10, 2, true
        ));
        clock.setInstant(T0.plusSeconds(310));
        evaluator.reloadPolicies();

        ToolExecutionResult afterRecoveryDenied = pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_LOW, Map.of("customerId", USER_ID), USER_ID, RiskLevel.LOW));
        assertFalse(afterRecoveryDenied.success());
        assertThat(afterRecoveryDenied.error()).contains("POLICY_DENIED");
        verify(restToolAdapter, never()).execute(any(), any());

        assertTrue(pipeline.execute(new ToolExecutionContext(
                AGENT_ID, TOOL_MEDIUM, Map.of("note", "recovered"), USER_ID, RiskLevel.MEDIUM)).success());
        verify(restToolAdapter, times(1)).execute(any(), any());

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid(), "PostgreSQL audit hash chain must remain valid");
        assertEquals(16L, verification.recordsChecked());
        assertNull(verification.corruptedSequence());
    }
}
