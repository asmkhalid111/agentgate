package com.agentgate.gateway.policy.service;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.policy.domain.*;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.registry.domain.RiskLevel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StructuredPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StructuredPolicyEvaluator.class);

    private final PolicyRepository repository;
    private final Clock clock;
    private final AtomicReference<List<PolicyRule>> cachedRules = new AtomicReference<>(Collections.emptyList());
    private volatile Instant lastSuccessfulRefresh;
    private volatile boolean dbAvailable = true;

    @Autowired
    public StructuredPolicyEvaluator(PolicyRepository repository, ObjectProvider<Clock> clockProvider) {
        this(repository, clockProvider != null ? clockProvider.getIfAvailable(Clock::systemUTC) : Clock.systemUTC());
    }

    public StructuredPolicyEvaluator(PolicyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public StructuredPolicyEvaluator(PolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.lastSuccessfulRefresh = this.clock.instant();
    }

    @PostConstruct
    public void init() {
        reloadPolicies();
    }

    /**
     * Hot reload policies every 5 seconds without restarting the gateway (satisfies FR3 <=10s requirement).
     */
    @Scheduled(fixedDelay = 5000)
    public void reloadPolicies() {
        try {
            List<PolicyRule> rules = repository.findAll();
            cachedRules.set(rules);
            lastSuccessfulRefresh = clock.instant();
            dbAvailable = true;
            log.debug("Policy cache refreshed: {} active rules loaded", rules.size());
        } catch (Exception e) {
            dbAvailable = false;
            log.error("Failed to reload policies from database: {}", e.getMessage());
        }
    }

    /**
     * Evaluates the requested tool call against active policies.
     * Enforces default-deny, dry-run mode, and QA4 fail-safe availability.
     */
    public PolicyDecision evaluate(ToolExecutionContext context) {
        long startTime = System.currentTimeMillis();

        // QA4 Fail-Safe Semantics Check
        if (!dbAvailable) {
            long evalMs = System.currentTimeMillis() - startTime;
            if (context.riskLevel() == RiskLevel.HIGH || context.riskLevel() == RiskLevel.MEDIUM) {
                log.warn("QA4 Fail-Closed: Policy store is unavailable, denying {} risk tool '{}'", context.riskLevel(), context.toolName());
                return PolicyDecision.deny("QA4_FAIL_CLOSED", "Policy Store Outage",
                        "Policy store unavailable: medium/high risk calls fail closed immediately", false, evalMs);
            }

            // Low-risk tools can continue using last-known policies for at most 5 minutes
            Duration outageDuration = Duration.between(lastSuccessfulRefresh, clock.instant());
            if (outageDuration.toMinutes() >= 5) {
                log.warn("QA4 Cutoff: Policy store unavailable > 5 minutes, cutting off low-risk tool '{}'", context.toolName());
                return PolicyDecision.deny("QA4_CUTOFF", "Policy Store Outage",
                        "Policy store unavailable > 5 minutes: low-risk fallback window expired", false, evalMs);
            }
            log.info("QA4 Low-Risk Fallback: Using cached policies (outage duration: {}s)", outageDuration.toSeconds());
        }

        List<PolicyRule> rules = cachedRules.get();
        for (PolicyRule rule : rules) {
            if (!matchesPattern(rule.agentPattern(), context.agentId())) {
                continue;
            }
            if (!matchesPattern(rule.toolPattern(), context.toolName())) {
                continue;
            }

            if (evaluateConditions(rule.conditions(), context.arguments(), context.userId())) {
                long evalMs = System.currentTimeMillis() - startTime;

                if (rule.dryRun()) {
                    log.warn("[POLICY_DRY_RUN] Rule '{}' would have executed {} for agent '{}' on tool '{}'",
                            rule.name(), rule.action(), context.agentId(), context.toolName());
                    return PolicyDecision.allow(rule.id(), rule.name(),
                            "Dry-run mode: permitted execution (would have decided " + rule.action() + ")", true, evalMs);
                }

                return new PolicyDecision(rule.action(), rule.id(), rule.name(), "Matched rule: " + rule.name(), false, evalMs);
            }
        }

        // FR3 Default Deny
        long evalMs = System.currentTimeMillis() - startTime;
        log.warn("Default Deny: No matching policy permitted agent '{}' to call tool '{}'", context.agentId(), context.toolName());
        return PolicyDecision.defaultDeny("Default Deny: No matching policy allowed this call", evalMs);
    }

    /**
     * Evaluates a draft rule in memory without database persistence (FR9).
     */
    public PolicyDecision evaluateDraft(PolicyRule draftRule, ToolExecutionContext context) {
        long startTime = System.currentTimeMillis();
        if (matchesPattern(draftRule.agentPattern(), context.agentId()) &&
                matchesPattern(draftRule.toolPattern(), context.toolName()) &&
                evaluateConditions(draftRule.conditions(), context.arguments(), context.userId())) {

            long evalMs = System.currentTimeMillis() - startTime;
            return new PolicyDecision(
                    draftRule.action(),
                    draftRule.id() != null ? draftRule.id() : "DRAFT",
                    draftRule.name() != null ? draftRule.name() : "Draft Rule Preview",
                    "Matched draft rule: " + draftRule.name(),
                    draftRule.dryRun(),
                    evalMs
            );
        }
        long evalMs = System.currentTimeMillis() - startTime;
        return PolicyDecision.defaultDeny("Draft rule did not match sample call", evalMs);
    }

    private boolean evaluateConditions(List<StructuredCondition> conditions, Map<String, Object> arguments, String userId) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (StructuredCondition cond : conditions) {
            if (!evaluateSingleCondition(cond, arguments, userId)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateSingleCondition(StructuredCondition cond, Map<String, Object> arguments, String userId) {
        Object actual = arguments != null ? arguments.get(cond.field()) : null;

        // User ownership check to prevent cross-customer access (QA1)
        if (cond.operator() == ConditionOperator.EQUALS_USER_ID) {
            if (userId == null || actual == null) {
                return false;
            }
            return userId.equalsIgnoreCase(String.valueOf(actual));
        }

        if (actual == null) {
            return false;
        }

        return switch (cond.operator()) {
            case EQ -> actual.toString().equalsIgnoreCase(String.valueOf(cond.value()));
            case NEQ -> !actual.toString().equalsIgnoreCase(String.valueOf(cond.value()));
            case CONTAINS -> actual.toString().contains(String.valueOf(cond.value()));
            case GT -> toDouble(actual) > toDouble(cond.value());
            case GTE -> toDouble(actual) >= toDouble(cond.value());
            case LT -> toDouble(actual) < toDouble(cond.value());
            case LTE -> toDouble(actual) <= toDouble(cond.value());
            default -> false;
        };
    }

    private double toDouble(Object val) {
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private boolean matchesPattern(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return value.toLowerCase().startsWith(prefix.toLowerCase());
        }
        return pattern.equalsIgnoreCase(value);
    }

    // For test control of QA4 outage simulation
    public void setSimulatedDbAvailability(boolean available, Instant lastRefresh) {
        this.dbAvailable = available;
        this.lastSuccessfulRefresh = lastRefresh;
    }
}
