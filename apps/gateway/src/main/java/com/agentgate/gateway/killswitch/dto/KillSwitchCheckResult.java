package com.agentgate.gateway.killswitch.dto;

import com.agentgate.gateway.killswitch.domain.KillSwitchType;

/**
 * Result of an authoritative kill switch check (FR8).
 * Enforces zero-information-disclosure to machine agents:
 * When blocked, always returns the generic safe message:
 * "KILL_SWITCH_ACTIVE: Tool execution is disabled by an emergency control."
 * Internal reasons, incident identifiers, and admin notes are retained strictly for protected internal logging/audit.
 */
public record KillSwitchCheckResult(
        boolean blocked,
        boolean unavailable,
        KillSwitchType type,
        String target,
        String message,
        String internalReason
) {
    public static final String GENERIC_BLOCKED_MESSAGE = "KILL_SWITCH_ACTIVE: Tool execution is disabled by an emergency control.";

    public static KillSwitchCheckResult allowed() {
        return new KillSwitchCheckResult(false, false, null, null, null, null);
    }

    public static KillSwitchCheckResult blocked(KillSwitchType type, String target, String reason) {
        return new KillSwitchCheckResult(true, false, type, target, GENERIC_BLOCKED_MESSAGE, reason);
    }

    public static KillSwitchCheckResult unavailable(String message) {
        return new KillSwitchCheckResult(true, true, null, null, message, null);
    }
}
