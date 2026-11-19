package com.agentgate.gateway.ratelimit;

/**
 * Service interface for atomic request rate limiting (FR6).
 */
public interface RateLimiter {

    /**
     * Atomically checks and reserves capacity for the specified agent and tool in the current
     * fixed 60-second window.
     *
     * @param agentId requesting agent client identifier
     * @param toolName governed tool name
     * @return RateLimitDecision indicating whether the request is allowed, exceeded, or unavailable
     */
    RateLimitDecision tryAcquire(String agentId, String toolName);
}
