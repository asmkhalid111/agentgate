package com.agentgate.gateway.killswitch.domain;

import java.time.Instant;

/**
 * Domain record representing an emergency kill switch state (FR8).
 */
public record KillSwitch(
        String id,
        KillSwitchType type,
        String target,
        boolean active,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
