package com.agentgate.gateway.audit.domain;

/**
 * State record representing the current sequence number and hash tip of the audit chain.
 */
public record AuditChainState(
        int id,
        long lastSequence,
        String lastHash
) {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
}
