package com.agentgate.gateway.policy;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.policy.domain.*;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
import com.agentgate.gateway.registry.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class PolicyEngineTest {

    @Autowired
    private PolicyRepository repository;

    @Autowired
    private StructuredPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        // Seed standard policies for test suite
        repository.save(new PolicyRule("pol-read-orders", "Customer Order Self-Read Policy", "*", "orders_get",
                List.of(new StructuredCondition("customerId", ConditionOperator.EQUALS_USER_ID, null)),
                PolicyAction.ALLOW, false, 10, 1, true));
        repository.save(new PolicyRule("pol-small-refund", "Finance Bot Low Value Refund", "agent-finance-*", "orders_refund",
                List.of(new StructuredCondition("amount", ConditionOperator.LTE, 2000)),
                PolicyAction.ALLOW, false, 20, 1, true));
        repository.save(new PolicyRule("pol-large-refund", "High Value Refund Approval", "agent-finance-*", "orders_refund",
                List.of(new StructuredCondition("amount", ConditionOperator.GT, 2000)),
                PolicyAction.REQUIRE_APPROVAL, false, 30, 1, true));
        repository.save(new PolicyRule("pol-dry-run-test", "Dry Run Audit Policy", "agent-test-*", "*",
                List.of(), PolicyAction.DENY, true, 50, 1, true));

        // Reset simulation status and reload
        evaluator.setSimulatedDbAvailability(true, Instant.now());
        evaluator.reloadPolicies();
    }

    @Test
    @DisplayName("Default Deny: Unmatched tool or agent call must be denied")
    void defaultDeny_shouldDenyUnmatchedCall() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "unknown-bot",
                "secret_tool",
                Map.of(),
                null,
                RiskLevel.LOW
        );

        PolicyDecision decision = evaluator.evaluate(ctx);
        assertEquals(PolicyAction.DENY, decision.action());
        assertTrue(decision.reason().contains("Default Deny"));
    }

    @Test
    @DisplayName("FR3 Policy Rules: Numeric comparison LTE allows refund <= 2000 BDT")
    void policyRules_shouldAllowRefundUnderThreshold() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-1", "amount", 1500),
                "usr-finance-operator",
                RiskLevel.HIGH
        );

        PolicyDecision decision = evaluator.evaluate(ctx);
        assertEquals(PolicyAction.ALLOW, decision.action());
        assertEquals("pol-small-refund", decision.matchedRuleId());
    }

    @Test
    @DisplayName("FR3 & FR4: Numeric comparison GT requires human approval for refund > 2000 BDT")
    void policyRules_shouldRequireApprovalForRefundOverThreshold() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-2", "amount", 3500),
                "usr-finance-operator",
                RiskLevel.HIGH
        );

        PolicyDecision decision = evaluator.evaluate(ctx);
        assertEquals(PolicyAction.REQUIRE_APPROVAL, decision.action());
        assertEquals("pol-large-refund", decision.matchedRuleId());
    }

    @Test
    @DisplayName("Ownership check: Allows customer reading own orders, blocks cross-customer read")
    void ownershipCheck_shouldPreventCrossCustomerRead() {
        // Customer reading own order (customerId == userId) -> ALLOW
        ToolExecutionContext selfRead = new ToolExecutionContext(
                "agent-support-bot",
                "orders_get",
                Map.of("customerId", "cust-101"),
                "cust-101",
                RiskLevel.LOW
        );
        PolicyDecision allowed = evaluator.evaluate(selfRead);
        assertEquals(PolicyAction.ALLOW, allowed.action());

        // Cross-customer read attempt (customerId != userId) -> DENY
        ToolExecutionContext crossCustomerRead = new ToolExecutionContext(
                "agent-support-bot",
                "orders_get",
                Map.of("customerId", "cust-999-victim"),
                "cust-101-attacker",
                RiskLevel.LOW
        );
        PolicyDecision denied = evaluator.evaluate(crossCustomerRead);
        assertEquals(PolicyAction.DENY, denied.action());
    }

    @Test
    @DisplayName("Dry-Run mode: Logs would-be action but allows execution with dryRun flag")
    void dryRun_shouldPermitExecutionWithFlag() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-test-runner",
                "any_tool",
                Map.of(),
                null,
                RiskLevel.LOW
        );

        PolicyDecision decision = evaluator.evaluate(ctx);
        assertEquals(PolicyAction.ALLOW, decision.action());
        assertTrue(decision.dryRun());
        assertEquals("pol-dry-run-test", decision.matchedRuleId());
    }

    @Test
    @DisplayName("Hot Reload: Updating policy in repository takes effect without application restart")
    void hotReload_shouldTakeEffectImmediately() {
        PolicyRule customRule = new PolicyRule(
                "pol-custom-vip",
                "VIP Bot Instant Access",
                "agent-vip-*",
                "vip_tool",
                List.of(),
                PolicyAction.ALLOW,
                false,
                5,
                1,
                true
        );

        repository.save(customRule);
        evaluator.reloadPolicies();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "agent-vip-bot",
                "vip_tool",
                Map.of(),
                null,
                RiskLevel.LOW
        );

        PolicyDecision decision = evaluator.evaluate(ctx);
        assertEquals(PolicyAction.ALLOW, decision.action());
        assertEquals("pol-custom-vip", decision.matchedRuleId());

        // Clean up
        repository.deleteById("pol-custom-vip");
        evaluator.reloadPolicies();
    }

    @Test
    @DisplayName("QA4 Fail-Safe: Medium/high risk fail closed on DB outage; low risk cutoff after 5m")
    void qa4_shouldEnforceFailSafeSemanticsOnStoreOutage() {
        // High risk call during outage -> FAIL CLOSED IMMEDIATELY
        evaluator.setSimulatedDbAvailability(false, Instant.now());
        ToolExecutionContext highRiskCall = new ToolExecutionContext(
                "agent-finance-bot",
                "orders_refund",
                Map.of("orderId", "ORD-1", "amount", 500),
                "usr-ops",
                RiskLevel.HIGH
        );
        PolicyDecision highDecision = evaluator.evaluate(highRiskCall);
        assertEquals(PolicyAction.DENY, highDecision.action());
        assertEquals("QA4_FAIL_CLOSED", highDecision.matchedRuleId());

        // Low risk call during outage within 5 minutes -> allowed on cached policy
        ToolExecutionContext lowRiskCall = new ToolExecutionContext(
                "agent-support-bot",
                "orders_get",
                Map.of("customerId", "cust-101"),
                "cust-101",
                RiskLevel.LOW
        );
        PolicyDecision lowDecisionWithin5m = evaluator.evaluate(lowRiskCall);
        assertEquals(PolicyAction.ALLOW, lowDecisionWithin5m.action());

        // Low risk call after 5 minutes outage window -> CUTOFF FAIL CLOSED
        evaluator.setSimulatedDbAvailability(false, Instant.now().minus(Duration.ofMinutes(6)));
        PolicyDecision lowDecisionExpired = evaluator.evaluate(lowRiskCall);
        assertEquals(PolicyAction.DENY, lowDecisionExpired.action());
        assertEquals("QA4_CUTOFF", lowDecisionExpired.matchedRuleId());
    }
}
