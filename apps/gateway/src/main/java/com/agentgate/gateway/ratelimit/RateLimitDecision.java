package com.agentgate.gateway.ratelimit;

/**
 * Outcome of an atomic rate limit check (FR6).
 */
public record RateLimitDecision(
        boolean allowed,
        DecisionType type,
        String message
) {
    public enum DecisionType {
        ALLOWED,
        RATE_LIMIT_EXCEEDED,
        RATE_LIMIT_UNAVAILABLE
    }

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, DecisionType.ALLOWED, null);
    }

    public static RateLimitDecision exceed(String message) {
        return new RateLimitDecision(false, DecisionType.RATE_LIMIT_EXCEEDED, message);
    }

    public static RateLimitDecision unavailable(String message) {
        return new RateLimitDecision(false, DecisionType.RATE_LIMIT_UNAVAILABLE, message);
    }
}
