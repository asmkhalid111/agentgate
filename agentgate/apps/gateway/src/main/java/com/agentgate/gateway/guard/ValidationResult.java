package com.agentgate.gateway.guard;

/**
 * Result of input argument validation against a tool's registered JSON schema.
 */
public record ValidationResult(boolean valid, String error) {

    public static ValidationResult accepted() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult rejected(String error) {
        return new ValidationResult(false, error);
    }
}
