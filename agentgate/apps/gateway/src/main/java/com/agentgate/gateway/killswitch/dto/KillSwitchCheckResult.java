package com.agentgate.gateway.killswitch.dto;

import com.agentgate.gateway.killswitch.domain.KillSwitchType;

/**
 * Result of an authoritative kill switch check (FR8).
 */
public record KillSwitchCheckResult(
        boolean blocked,
        boolean unavailable,
        KillSwitchType type,
        String target,
        String message
) {
    public static KillSwitchCheckResult allowed() {
        return new KillSwitchCheckResult(false, false, null, null, null);
    }

    public static KillSwitchCheckResult blocked(KillSwitchType type, String target, String reason) {
        String msg = String.format("KILL_SWITCH_ACTIVE: Execution disabled for %s [%s]. Reason: %s",
                type, target, reason != null ? reason : "Emergency switch active");
        return new KillSwitchCheckResult(true, false, type, target, msg);
    }

    public static KillSwitchCheckResult unavailable(String message) {
        return new KillSwitchCheckResult(true, true, null, null, message);
    }
}
