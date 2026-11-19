package com.agentgate.gateway.killswitch.dto;

import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for activating or deactivating an emergency kill switch.
 */
public record KillSwitchRequest(
        @NotNull(message = "Switch type is required (AGENT, TOOL, GLOBAL_WRITE)")
        KillSwitchType type,

        String target,

        @NotBlank(message = "Reason is required for activating emergency kill switch")
        String reason
) {
}
