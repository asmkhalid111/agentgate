package com.agentgate.gateway.killswitch.dto;

import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;

import java.time.Instant;

/**
 * REST response representing a kill switch status.
 */
public record KillSwitchResponse(
        String id,
        KillSwitchType type,
        String target,
        boolean active,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static KillSwitchResponse from(KillSwitch ks) {
        if (ks == null) {
            return null;
        }
        return new KillSwitchResponse(
                ks.id(),
                ks.type(),
                ks.target(),
                ks.active(),
                ks.reason(),
                ks.createdBy(),
                ks.createdAt(),
                ks.updatedAt()
        );
    }
}
