package com.agentgate.gateway.ratelimit;

/**
 * Result of an hourly write cap admission check (FR6).
 */
public record WriteCapDecision(
        boolean allowed,
        DecisionType type,
        String message
) {
    public enum DecisionType {
        ALLOWED,
        WRITE_CAP_EXCEEDED,
        WRITE_CAP_UNAVAILABLE
    }

    public static WriteCapDecision allow() {
        return new WriteCapDecision(true, DecisionType.ALLOWED, null);
    }

    public static WriteCapDecision exceed(String message) {
        return new WriteCapDecision(false, DecisionType.WRITE_CAP_EXCEEDED, message);
    }

    public static WriteCapDecision unavailable(String message) {
        return new WriteCapDecision(false, DecisionType.WRITE_CAP_UNAVAILABLE, message);
    }
}
