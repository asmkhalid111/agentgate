package com.agentgate.gateway.killswitch.service;

import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.dto.KillSwitchCheckResult;
import com.agentgate.gateway.killswitch.repository.KillSwitchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing emergency kill switches (FR8).
 * Provides atomic activation/deactivation and authoritative fail-closed status verification.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);
    private static final String DEFAULT_GLOBAL_TARGET = "GLOBAL";

    private final KillSwitchRepository killSwitchRepository;

    public KillSwitchService(KillSwitchRepository killSwitchRepository) {
        this.killSwitchRepository = killSwitchRepository;
    }

    /**
     * Authoritatively checks if tool execution is blocked by any active kill switch.
     * <p>
     * <b>FAIL-CLOSED SAFETY INVARIANT:</b>
     * If the underlying database is unreachable or throws any exception,
     * this method fails closed and returns an unavailable result, safely halting execution
     * without leaking internal connection or database details.
     * </p>
     *
     * @param agentId  calling agent identifier
     * @param toolName target tool name
     * @param isWrite  whether the tool performs mutating operations
     * @return decision indicating whether execution is allowed or blocked
     */
    public KillSwitchCheckResult checkBlocked(String agentId, String toolName, boolean isWrite) {
        try {
            Optional<KillSwitch> activeSwitch = killSwitchRepository.findActiveSwitch(agentId, toolName, isWrite);
            if (activeSwitch.isPresent()) {
                KillSwitch ks = activeSwitch.get();
                log.warn("Execution blocked by emergency kill switch: type={}, target={}, reason='{}'",
                        ks.type(), ks.target(), ks.reason());
                return KillSwitchCheckResult.blocked(ks.type(), ks.target(), ks.reason());
            }
            return KillSwitchCheckResult.allowed();
        } catch (Exception e) {
            log.error("Emergency kill switch database lookup failed; failing closed: {}", e.getMessage());
            return KillSwitchCheckResult.unavailable(
                    "KILL_SWITCH_UNAVAILABLE: Emergency controls temporarily unavailable."
            );
        }
    }

    /**
     * Activates an emergency kill switch idempotently.
     */
    public KillSwitch activate(KillSwitchType type, String target, String reason, String adminUsername) {
        if (type == null) {
            throw new IllegalArgumentException("Kill switch type is required");
        }
        String normalizedTarget = normalizeTarget(type, target);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason is required for activating an emergency kill switch");
        }
        String admin = (adminUsername != null && !adminUsername.isBlank()) ? adminUsername : "SYSTEM_ADMIN";

        Instant now = Instant.now();
        String id = "ks-" + UUID.randomUUID().toString();
        KillSwitch ks = new KillSwitch(id, type, normalizedTarget, true, reason.trim(), admin, now, now);

        killSwitchRepository.upsert(ks);
        log.warn("EMERGENCY KILL SWITCH ACTIVATED: type={}, target={}, by='{}', reason='{}'",
                type, normalizedTarget, admin, reason);

        return killSwitchRepository.findByTypeAndTarget(type, normalizedTarget).orElse(ks);
    }

    /**
     * Deactivates an emergency kill switch idempotently.
     */
    public Optional<KillSwitch> deactivate(KillSwitchType type, String target, String adminUsername) {
        if (type == null) {
            throw new IllegalArgumentException("Kill switch type is required");
        }
        String normalizedTarget = normalizeTarget(type, target);
        Instant now = Instant.now();

        boolean changed = killSwitchRepository.deactivate(type, normalizedTarget, now);
        if (changed) {
            log.info("Emergency kill switch deactivated: type={}, target={}, by='{}'",
                    type, normalizedTarget, adminUsername);
        }
        return killSwitchRepository.findByTypeAndTarget(type, normalizedTarget);
    }

    /**
     * Retrieves a specific switch.
     */
    public Optional<KillSwitch> findSwitch(KillSwitchType type, String target) {
        if (type == null) return Optional.empty();
        return killSwitchRepository.findByTypeAndTarget(type, normalizeTarget(type, target));
    }

    /**
     * Lists kill switches with optional active filter and pagination.
     */
    public List<KillSwitch> listSwitches(Boolean activeOnly, int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        int offset = boundedPage * boundedSize;
        return killSwitchRepository.findAll(activeOnly, boundedSize, offset);
    }

    /**
     * Counts kill switches with optional active filter.
     */
    public long countSwitches(Boolean activeOnly) {
        return killSwitchRepository.count(activeOnly);
    }

    private String normalizeTarget(KillSwitchType type, String target) {
        if (type == KillSwitchType.GLOBAL_WRITE) {
            if (target == null || target.isBlank()) {
                return DEFAULT_GLOBAL_TARGET;
            }
            return target.trim();
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Target identity is required for switch type: " + type);
        }
        return target.trim();
    }
}
