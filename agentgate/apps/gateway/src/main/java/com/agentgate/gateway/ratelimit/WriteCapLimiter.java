package com.agentgate.gateway.ratelimit;

/**
 * Interface for checking and enforcing the rolling hourly write dispatch cap (FR6).
 */
public interface WriteCapLimiter {

    /**
     * Attempts to acquire one write dispatch permission for the specified agent.
     * Enforces a hard blast-radius cap of at most 20 write dispatches within any rolling 60-minute window.
     *
     * @param agentId the authenticated agent client ID
     * @return the write cap decision
     */
    WriteCapDecision tryAcquireWrite(String agentId);
}
