package com.agentgate.gateway.approval.exception;

/**
 * Thrown when an approval request cannot be found by its identifier.
 * Extends IllegalArgumentException for backward compatibility.
 */
public class ApprovalNotFoundException extends IllegalArgumentException {
    public ApprovalNotFoundException(String message) {
        super(message);
    }
}
