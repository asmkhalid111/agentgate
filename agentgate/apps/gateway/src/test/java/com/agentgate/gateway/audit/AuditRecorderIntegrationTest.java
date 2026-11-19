package com.agentgate.gateway.audit;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditRecordDraft;
import com.agentgate.gateway.audit.domain.AuditVerificationResult;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.audit.service.AuditHashEngine;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.audit.service.AuditVerificationService;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.guard.JsonSchemaValidationGuard;
import com.agentgate.gateway.guard.PiiRedactionGuard;
import com.agentgate.gateway.guard.ResponseTruncationGuard;
import com.agentgate.gateway.identity.AgentAuthenticationToken;
import com.agentgate.gateway.identity.AgentIdentity;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.repository.KillSwitchRepository;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.agentgate.gateway.mcp.ApprovalResultMcpProvider;
import com.agentgate.gateway.pipeline.ApprovedToolExecutionService;
import com.agentgate.gateway.pipeline.RestToolAdapter;
import com.agentgate.gateway.pipeline.ToolExecutionPipeline;
import com.agentgate.gateway.pipeline.ToolExecutionResult;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Tamper-Evident Audit Recording & Cryptographic Hash Chain Integration Tests (FR7)")
class AuditRecorderIntegrationTest {

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Autowired
    private ApprovedToolExecutionService approvedExecutionService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalResultMcpProvider approvalResultMcpProvider;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private KillSwitchRepository killSwitchRepository;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private AuditHashEngine auditHashEngine;

    @Autowired
    private AuditVerificationService auditVerificationService;

    @Autowired
    private RegisteredToolRepository toolRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private StructuredPolicyEvaluator policyEvaluator;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JsonSchemaValidationGuard validationGuard;

    @Autowired
    private PiiRedactionGuard piiRedactionGuard;

    @Autowired
    private ResponseTruncationGuard responseTruncationGuard;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private RestToolAdapter restToolAdapter;

    @MockitoSpyBean
    private RateLimiter rateLimiter;

    @MockitoSpyBean
    private WriteCapLimiter writeCapLimiter;

    private static final String AGENT_TEST = "agent-audit-tester";
    private static final String USER_TEST = "user-alice";

    private static final String TOOL_READ = "audit_read_tool";
    private static final String TOOL_WRITE = "audit_write_tool";
    private static final String TOOL_APPROVAL = "audit_approval_tool";

    @BeforeEach
    void setUp() {
        auditRepository.resetToGenesis();
        killSwitchRepository.deleteAll();
        approvalRepository.deleteAll();

        // Default mock adapter behavior: success with 15ms latency
        doReturn(ToolExecutionResult.success(Map.of("status", "ok"), 15L))
                .when(restToolAdapter).execute(any(), any());

        // Default rate limiter and write cap: allowed
        doReturn(RateLimitDecision.allow()).when(rateLimiter).tryAcquire(any(), any());
        doReturn(WriteCapDecision.allow()).when(writeCapLimiter).tryAcquireWrite(any());

        // Register tools if not present
        toolRepository.findByName(TOOL_READ).ifPresentOrElse(t -> {}, () -> {
            RegisteredTool readTool = new RegisteredTool(
                    "tool-audit-read-1", TOOL_READ, "Read Tool",
                    "{\"type\":\"object\"}", RiskLevel.LOW, "team-audit",
                    "http://localhost:8082/api/read", ProtocolType.REST,
                    "GET", true, Instant.now(), Instant.now()
            );
            toolRepository.save(readTool);
        });
        toolRepository.addPermission(TOOL_READ, AGENT_TEST);

        toolRepository.findByName(TOOL_WRITE).ifPresentOrElse(t -> {}, () -> {
            RegisteredTool writeTool = new RegisteredTool(
                    "tool-audit-write-1", TOOL_WRITE, "Write Tool",
                    "{\"type\":\"object\"}", RiskLevel.MEDIUM, "team-audit",
                    "http://localhost:8082/api/write", ProtocolType.REST,
                    "POST", true, Instant.now(), Instant.now()
            );
            toolRepository.save(writeTool);
        });
        toolRepository.addPermission(TOOL_WRITE, AGENT_TEST);

        toolRepository.findByName(TOOL_APPROVAL).ifPresentOrElse(t -> {}, () -> {
            RegisteredTool apprTool = new RegisteredTool(
                    "tool-audit-appr-1", TOOL_APPROVAL, "Approval Tool",
                    "{\"type\":\"object\"}", RiskLevel.HIGH, "team-audit",
                    "http://localhost:8082/api/action", ProtocolType.REST,
                    "POST", true, Instant.now(), Instant.now()
            );
            toolRepository.save(apprTool);
        });
        toolRepository.addPermission(TOOL_APPROVAL, AGENT_TEST);

        // Ensure clear policies
        policyRepository.deleteById("pol-audit-deny");
        policyRepository.deleteById("pol-audit-approval");
        policyRepository.deleteById("pol-large-payment");

        PolicyRule allowRule = new PolicyRule(
                "pol-audit-allow-all",
                "Allow Audit Test Tools",
                "*",
                "audit_*",
                Collections.emptyList(),
                PolicyAction.ALLOW,
                false,
                100,
                1,
                true
        );
        policyRepository.save(allowRule);
        policyEvaluator.reloadPolicies();
    }

    @Test
    @DisplayName("1. Direct tool execution records complete audit trace with latency and success")
    void directExecution_recordsAuditTraceWithLatencyAndSuccess() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_READ, Map.of("query", "status"), USER_TEST, RiskLevel.LOW, Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertTrue(result.success());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(1, events.size());

        AuditEvent event = events.get(0);
        assertEquals(1, event.sequenceNumber());
        assertEquals("DIRECT_EXECUTION", event.eventType());
        assertEquals(AGENT_TEST, event.agentId());
        assertEquals(USER_TEST, event.userId());
        assertEquals(TOOL_READ, event.toolName());
        assertEquals("SUCCESS", event.outcome());
        assertEquals(15L, event.latencyMs());
        assertEquals(AuditChainState.GENESIS_HASH, event.prevRecordHash());
        assertNotNull(event.recordHash());

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
        assertEquals(1, verification.recordsChecked());
    }

    @Test
    @DisplayName("2. Mutating write operation records mandatory pre-dispatch intent and terminal outcome")
    void writeOperation_recordsPreDispatchIntentAndSuccessOutcome() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_WRITE, Map.of("amount", 250), USER_TEST, RiskLevel.MEDIUM, Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertTrue(result.success());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(2, events.size());

        // Event 1: Pre-dispatch write intent
        AuditEvent intent = events.get(0);
        assertEquals(1, intent.sequenceNumber());
        assertEquals("WRITE_DISPATCH_INTENT", intent.eventType());
        assertEquals(TOOL_WRITE, intent.toolName());
        assertEquals("DISPATCH_INTENT", intent.decision());
        assertEquals("PENDING_DISPATCH", intent.outcome());
        assertEquals(AuditChainState.GENESIS_HASH, intent.prevRecordHash());

        // Event 2: Terminal execution
        AuditEvent exec = events.get(1);
        assertEquals(2, exec.sequenceNumber());
        assertEquals("DIRECT_EXECUTION", exec.eventType());
        assertEquals("SUCCESS", exec.outcome());
        assertEquals(intent.recordHash(), exec.prevRecordHash());

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
        assertEquals(2, verification.recordsChecked());
    }

    @Test
    @DisplayName("3. Write operation fails closed when pre-dispatch audit logging fails")
    void writeOperation_failsClosedWhenPreDispatchAuditFails() {
        AuditRecorder unproxied = new AuditRecorder(auditRepository, auditHashEngine, piiRedactionGuard, objectMapper);
        AuditRecorder spyRecorder = spy(unproxied);
        doThrow(new RuntimeException("Simulated audit disk failure"))
                .when(spyRecorder).recordWriteDispatchIntent(any(), any(), any(), any(), any(), any(), any());

        ToolExecutionPipeline testPipeline = new ToolExecutionPipeline(
                toolRepository, List.of(restToolAdapter), policyEvaluator, validationGuard, piiRedactionGuard, responseTruncationGuard,
                approvalService, rateLimiter, writeCapLimiter, killSwitchService, spyRecorder, 900L
        );

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_WRITE, Map.of("action", "delete"), USER_TEST, RiskLevel.MEDIUM, Set.of()
        );

        ToolExecutionResult result = testPipeline.execute(ctx);
        assertFalse(result.success());
        assertTrue(result.error().contains("AUDIT_WRITE_INTENT_PERSISTENCE_FAILED"));

        // Verify downstream adapter was NEVER invoked
        verify(restToolAdapter, never()).execute(any(), any());
    }

    @Test
    @DisplayName("4. Pre-dispatch write intent survives when downstream execution fails or throws")
    void preDispatchWriteIntent_survivesWhenDownstreamExecutionFails() {
        doThrow(new RuntimeException("Downstream network timeout"))
                .when(restToolAdapter).execute(any(), any());

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_WRITE, Map.of("action", "transfer"), USER_TEST, RiskLevel.MEDIUM, Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertFalse(result.success());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(2, events.size());

        // Intent event was committed
        AuditEvent intent = events.get(0);
        assertEquals("WRITE_DISPATCH_INTENT", intent.eventType());
        assertEquals("PENDING_DISPATCH", intent.outcome());

        // Failure event was also committed
        AuditEvent failEvent = events.get(1);
        assertEquals("DIRECT_EXECUTION", failEvent.eventType());
        assertTrue(failEvent.outcome().contains("FAILED") || failEvent.outcome().contains("INDETERMINATE"));
        assertTrue(failEvent.errorMessage().contains("Downstream network timeout"));

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
    }

    @Test
    @DisplayName("5. Governance denials are audited accurately for policy, kill switch, and rate limit")
    void governanceDenials_auditedAccurately() {
        // A. Policy DENY
        PolicyRule denyRule = new PolicyRule(
                "pol-audit-deny", "Deny Audit Rule", "*", TOOL_READ,
                Collections.emptyList(), PolicyAction.DENY, false, 1, 1, true
        );
        policyRepository.save(denyRule);
        policyEvaluator.reloadPolicies();

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_READ, Map.of("param", "1"), USER_TEST, RiskLevel.LOW, Set.of()
        );
        ToolExecutionResult res = pipeline.execute(ctx);
        assertFalse(res.success());
        assertTrue(res.error().contains("POLICY_DENIED"));

        policyRepository.deleteById("pol-audit-deny");
        policyEvaluator.reloadPolicies();

        // B. Ingress Emergency Kill Switch
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_TEST, "Audit test switch", "admin.audit");
        ToolExecutionResult resKill = pipeline.execute(ctx);
        assertFalse(resKill.success());
        assertTrue(resKill.error().contains("KILL_SWITCH_ACTIVE"));

        killSwitchService.deactivate(KillSwitchType.AGENT, AGENT_TEST, "admin.audit");

        // C. Rate Limit Exceeded
        doReturn(RateLimitDecision.exceed("Rate limit exceeded")).when(rateLimiter).tryAcquire(any(), any());
        ToolExecutionResult resRate = pipeline.execute(ctx);
        assertFalse(resRate.success());
        assertTrue(resRate.error().contains("Rate limit exceeded"));

        doReturn(RateLimitDecision.allow()).when(rateLimiter).tryAcquire(any(), any());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(3, events.size());

        assertEquals("POLICY_DENIED", events.get(0).decision());
        assertEquals("KILL_SWITCH_BLOCKED", events.get(1).decision());
        assertEquals("RATE_LIMIT_EXCEEDED", events.get(2).decision());

        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
        assertEquals(3, verification.recordsChecked());
    }

    @Test
    @DisplayName("6. Human approval lifecycle events are correlated by approval_id across all transitions")
    void approvalLifecycle_correlatedByApprovalIdAcrossAllTransitions() {
        // 1. Create approval request
        ApprovalRequest req = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-large-payment",
                Map.of("amount", 10000), Duration.ofMinutes(15)
        );
        assertNotNull(req.id());

        // 2. Approve request
        ApprovalRequest approved = approvalService.approve(req.id(), "admin.human", null);
        assertEquals(ApprovalStatus.APPROVED, approved.status());

        // 3. Background execution
        approvedExecutionService.executeApproved(req.id());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        // Expected:
        // 1: APPROVAL_REQUESTED
        // 2: APPROVAL_DECISION (APPROVED)
        // 3: WRITE_DISPATCH_INTENT
        // 4: APPROVED_EXECUTION (SUCCESS)
        assertEquals(4, events.size());

        for (AuditEvent ev : events) {
            assertEquals(req.id(), ev.approvalId());
        }

        assertEquals("APPROVAL_REQUESTED", events.get(0).eventType());
        assertEquals("APPROVAL_DECISION", events.get(1).eventType());
        assertEquals("admin.human", events.get(1).actorId());
        assertEquals("WRITE_DISPATCH_INTENT", events.get(2).eventType());
        assertEquals("APPROVED_EXECUTION", events.get(3).eventType());
        assertEquals("SUCCESS", events.get(3).outcome());

        // Integrity verification
        AuditVerificationResult verification = auditVerificationService.verifyIntegrity();
        assertTrue(verification.valid());
        assertEquals(4, verification.recordsChecked());
    }

    @Test
    @DisplayName("7. Human approval rejection lifecycle event is correlated by approval_id")
    void approvalLifecycle_rejection_correlatedByApprovalId() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-large-payment",
                Map.of("amount", 20000), Duration.ofMinutes(15)
        );

        approvalService.reject(req.id(), "admin.rejector", "Fraudulent request suspicious pattern");

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(2, events.size());

        assertEquals(req.id(), events.get(0).approvalId());
        assertEquals("APPROVAL_REQUESTED", events.get(0).eventType());

        assertEquals(req.id(), events.get(1).approvalId());
        assertEquals("APPROVAL_DECISION", events.get(1).eventType());
        assertEquals("REJECTED", events.get(1).decision());
        assertEquals("admin.rejector", events.get(1).actorId());
        assertEquals("Fraudulent request suspicious pattern", events.get(1).errorMessage());

        assertTrue(auditVerificationService.verifyIntegrity().valid());
    }

    @Test
    @DisplayName("8. Bulk kill switch activation cancels pending approvals and records audit events")
    void approvalLifecycle_bulkKillSwitchCancellation_recordsAuditEvents() {
        ApprovalRequest req1 = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-1", Map.of("id", 1), Duration.ofMinutes(15)
        );
        ApprovalRequest req2 = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-2", Map.of("id", 2), Duration.ofMinutes(15)
        );

        // Activate kill switch on agent -> cancels both pending approvals
        killSwitchService.activate(KillSwitchType.AGENT, AGENT_TEST, "Compromised agent key", "security.lead");

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        // 2 APPROVAL_REQUESTED + 2 APPROVAL_DECISION (CANCELLED) = 4 events
        assertEquals(4, events.size());

        List<AuditEvent> cancelledEvents = events.stream()
                .filter(e -> "APPROVAL_DECISION".equals(e.eventType()))
                .toList();
        assertEquals(2, cancelledEvents.size());

        for (AuditEvent cancelEvent : cancelledEvents) {
            assertEquals("CANCELLED", cancelEvent.decision());
            assertEquals("security.lead", cancelEvent.actorId());
            assertTrue(cancelEvent.errorMessage().contains("Compromised agent key"));
        }

        assertTrue(auditVerificationService.verifyIntegrity().valid());
    }

    @Test
    @DisplayName("9. Approval expiration sweep transitions overdue requests and records audit events")
    void approvalLifecycle_expirationSweep_recordsAuditEvents() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-1", Map.of("id", 1), Duration.ofSeconds(1)
        );

        // Manually backdate expiration in test DB
        jdbcClient.sql("UPDATE approval_requests SET expires_at = :past WHERE id = :id")
                .param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(10)))
                .param("id", req.id())
                .update();

        int expired = approvalService.expirePendingRequests();
        assertTrue(expired >= 1);

        List<AuditEvent> events = auditRepository.findByApprovalId(req.id());
        assertEquals(2, events.size());

        AuditEvent expireEvent = events.get(1);
        assertEquals("APPROVAL_DECISION", expireEvent.eventType());
        assertEquals("EXPIRED", expireEvent.decision());
        assertEquals("SYSTEM", expireEvent.actorId());

        assertTrue(auditVerificationService.verifyIntegrity().valid());
    }

    @Test
    @DisplayName("10. Builtin MCP tool get_approval_result is audited without leaking secrets")
    void builtinToolExecution_auditedForGetApprovalResult() {
        ApprovalRequest req = approvalService.createRequest(
                AGENT_TEST, USER_TEST, TOOL_APPROVAL, "pol-1", Map.of("id", 1), Duration.ofMinutes(15)
        );

        AgentIdentity identity = new AgentIdentity(AGENT_TEST, Set.of("agent:invoke"), Set.of("ROLE_AGENT"));
        SecurityContextHolder.getContext().setAuthentication(
                new AgentAuthenticationToken(identity, "mock-credentials", List.of(new SimpleGrantedAuthority("SCOPE_agent:invoke")))
        );
        try {
            Map<String, Object> result = approvalResultMcpProvider.getApprovalResult(req.id());
            assertNotNull(result);
            assertEquals(req.id(), result.get("approvalId"));

            List<AuditEvent> events = auditRepository.findAllOrderBySequence();
            // 1 APPROVAL_REQUESTED + 1 BUILTIN_TOOL_EXECUTION = 2 events
            assertEquals(2, events.size());

            AuditEvent mcpEvent = events.get(1);
            assertEquals("BUILTIN_TOOL_EXECUTION", mcpEvent.eventType());
            assertEquals("get_approval_result", mcpEvent.toolName());
            assertEquals(AGENT_TEST, mcpEvent.agentId());
            assertEquals(req.id(), mcpEvent.approvalId());
            assertEquals("SUCCESS", mcpEvent.outcome());

            assertTrue(auditVerificationService.verifyIntegrity().valid());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("11. Privacy & secret scrubbing redacts credential field names and sensitive PII")
    void privacyScrubbing_scrubsCredentialsAndPii() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("apiKey", "secret-key-12345");
        args.put("password", "Sup3rS3cret!");
        args.put("auth_token", "jwt.token.here");
        args.put("client_secret", "oauth-secret");
        args.put("email", "john.doe@example.com");
        args.put("phone", "01712345678"); // Bangladeshi mobile phone
        args.put("nid", "19901234567890123"); // Bangladeshi NID 17 digits
        args.put("validField", "safe value");

        ToolExecutionContext ctx = new ToolExecutionContext(
                AGENT_TEST, TOOL_READ, args, USER_TEST, RiskLevel.LOW, Set.of()
        );

        ToolExecutionResult result = pipeline.execute(ctx);
        assertTrue(result.success());

        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(1, events.size());

        String json = events.get(0).redactedArgumentsJson();
        assertNotNull(json);

        // Verify secrets are scrubbed
        assertFalse(json.contains("secret-key-12345"));
        assertFalse(json.contains("Sup3rS3cret!"));
        assertFalse(json.contains("jwt.token.here"));
        assertFalse(json.contains("oauth-secret"));
        assertTrue(json.contains("\"apiKey\":\"[REDACTED_SECRET]\""));
        assertTrue(json.contains("\"password\":\"[REDACTED_SECRET]\""));
        assertTrue(json.contains("\"auth_token\":\"[REDACTED_SECRET]\""));
        assertTrue(json.contains("\"client_secret\":\"[REDACTED_SECRET]\""));

        // Verify PII is scrubbed
        assertFalse(json.contains("john.doe@example.com"));
        assertFalse(json.contains("01712345678"));
        assertFalse(json.contains("19901234567890123"));
        assertTrue(json.contains("[REDACTED_EMAIL]"));
        assertTrue(json.contains("[REDACTED_PHONE]"));
        assertTrue(json.contains("[REDACTED_NID]"));

        // Safe field is preserved
        assertTrue(json.contains("\"validField\":\"safe value\""));

        assertTrue(auditVerificationService.verifyIntegrity().valid());
    }

    @Test
    @DisplayName("12. Canonical hashing covers all 16 business and chain linkage fields")
    void canonicalHashing_coversAll16BusinessFields() {
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-1", "user-1", "actor-1",
                "tool-1", "{\"arg\":1}", "rule-1", "Rule Name", "ALLOW",
                "appr-1", "SUCCESS", 50L, "no error"
        );

        String originalHash = auditHashEngine.computeRecordHash(1L, AuditChainState.GENESIS_HASH, draft);

        // Mutating any of the fields must produce a different hash:
        assertNotEquals(originalHash, auditHashEngine.computeRecordHash(2L, AuditChainState.GENESIS_HASH, draft)); // sequence
        assertNotEquals(originalHash, auditHashEngine.computeRecordHash(1L, "1111111111111111111111111111111111111111111111111111111111111111", draft)); // prevHash

        AuditRecordDraft mutatedAgent = new AuditRecordDraft(
                draft.eventTimestamp(), draft.eventType(), "agent-MUTATED", draft.userId(), draft.actorId(),
                draft.toolName(), draft.redactedArgumentsJson(), draft.policyRuleId(), draft.policyRuleName(),
                draft.decision(), draft.approvalId(), draft.outcome(), draft.latencyMs(), draft.errorMessage()
        );
        assertNotEquals(originalHash, auditHashEngine.computeRecordHash(1L, AuditChainState.GENESIS_HASH, mutatedAgent));

        AuditRecordDraft mutatedOutcome = new AuditRecordDraft(
                draft.eventTimestamp(), draft.eventType(), draft.agentId(), draft.userId(), draft.actorId(),
                draft.toolName(), draft.redactedArgumentsJson(), draft.policyRuleId(), draft.policyRuleName(),
                draft.decision(), draft.approvalId(), "FAILED", draft.latencyMs(), draft.errorMessage()
        );
        assertNotEquals(originalHash, auditHashEngine.computeRecordHash(1L, AuditChainState.GENESIS_HASH, mutatedOutcome));
    }

    @Test
    @DisplayName("13. Timestamp millisecond normalization preserves bit-level hash across JDBC round-trip")
    void timestampNormalization_preservesHashAcrossJdbcRoundTrip() {
        AuditRecordDraft draft = new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", AGENT_TEST, USER_TEST, AGENT_TEST,
                TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 10L, null
        );

        AuditEvent persisted = auditRecorder.append(draft);
        assertNotNull(persisted);

        AuditEvent retrieved = auditRepository.findBySequenceNumber(persisted.sequenceNumber()).orElseThrow();
        assertEquals(persisted.recordHash(), retrieved.recordHash());

        String recomputedHash = auditHashEngine.computeRecordHash(retrieved);
        assertEquals(retrieved.recordHash(), recomputedHash);
    }

    @Test
    @DisplayName("14. Concurrent appends produce contiguous chain without gaps, duplicates, or forks")
    void concurrentAppends_produceContiguousChainWithoutGapsOrForks() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<AuditEvent>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    AuditRecordDraft draft = new AuditRecordDraft(
                            Instant.now(), "CONCURRENT_TEST", "agent-" + idx, null, "agent-" + idx,
                            TOOL_READ, "{\"idx\":" + idx + "}", null, null, "ALLOW", null, "SUCCESS", 5L, null
                    );
                    return auditRecorder.append(draft);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed);
        executor.shutdown();

        List<AuditEvent> all = auditRepository.findAllOrderBySequence();
        assertEquals(threadCount, all.size());

        // Verify sequence numbers are exactly 1 to threadCount
        for (int i = 0; i < threadCount; i++) {
            assertEquals((long) (i + 1), all.get(i).sequenceNumber());
            if (i > 0) {
                assertEquals(all.get(i - 1).recordHash(), all.get(i).prevRecordHash());
            }
        }

        AuditVerificationResult result = auditVerificationService.verifyIntegrity();
        assertTrue(result.valid());
        assertEquals(threadCount, result.recordsChecked());
    }

    @Test
    @DisplayName("15. Cryptographic verification detects modified record content")
    void tamperDetection_detectsModifiedRecord() {
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-1", null, "agent-1", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-2", null, "agent-2", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-3", null, "agent-3", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));

        assertTrue(auditVerificationService.verifyIntegrity().valid());

        // Tamper with record 2 outcome in database directly
        jdbcClient.sql("UPDATE audit_log SET outcome = 'TAMPERED' WHERE sequence_number = 2").update();

        AuditVerificationResult result = auditVerificationService.verifyIntegrity();
        assertFalse(result.valid());
        assertEquals(2L, result.corruptedSequence());
        assertTrue(result.failureReason().contains("Tampered record"));
    }

    @Test
    @DisplayName("16. Cryptographic verification detects deleted middle record (sequence gap)")
    void tamperDetection_detectsDeletedMiddleRecord() {
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-1", null, "agent-1", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-2", null, "agent-2", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-3", null, "agent-3", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));

        // Delete sequence 2
        jdbcClient.sql("DELETE FROM audit_log WHERE sequence_number = 2").update();

        AuditVerificationResult result = auditVerificationService.verifyIntegrity();
        assertFalse(result.valid());
        assertTrue(result.failureReason().contains("Sequence gap or discontinuity") || result.failureReason().contains("Tip sequence mismatch"));
    }

    @Test
    @DisplayName("17. Cryptographic verification detects deleted tail record (tip mismatch)")
    void tamperDetection_detectsDeletedTailRecord() {
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-1", null, "agent-1", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-2", null, "agent-2", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));
        auditRecorder.append(new AuditRecordDraft(Instant.now(), "TYPE", "agent-3", null, "agent-3", TOOL_READ, "{}", null, null, "ALLOW", null, "SUCCESS", 5L, null));

        // Delete sequence 3 from log without updating audit_chain_state
        jdbcClient.sql("DELETE FROM audit_log WHERE sequence_number = 3").update();

        AuditVerificationResult result = auditVerificationService.verifyIntegrity();
        assertFalse(result.valid());
        assertTrue(result.failureReason().contains("Tip sequence mismatch"));
    }

    @Test
    @DisplayName("18. REQUIRES_NEW propagation ensures audit record commits even if outer transaction rolls back")
    void requiresNewPropagation_commitsAuditEvenIfCallerRollsBack() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> txTemplate.execute(status -> {
            auditRecorder.recordDirectToolExecution(
                    AGENT_TEST, USER_TEST, TOOL_READ, Map.of("step", "tx_test"),
                    null, null, "ALLOW", 10L, "SUCCESS", null
            );
            throw new RuntimeException("Simulated outer transaction rollback");
        }));

        // Verify audit event survived outer rollback
        List<AuditEvent> events = auditRepository.findAllOrderBySequence();
        assertEquals(1, events.size());
        assertEquals(1L, events.get(0).sequenceNumber());
        assertEquals("DIRECT_EXECUTION", events.get(0).eventType());
        assertTrue(auditVerificationService.verifyIntegrity().valid());
    }
}
