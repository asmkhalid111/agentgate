package com.agentgate.gateway.killswitch.dto;

import java.util.List;

/**
 * Paginated wrapper for kill switch responses.
 */
public record PagedKillSwitchResponse(
        List<KillSwitchResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
