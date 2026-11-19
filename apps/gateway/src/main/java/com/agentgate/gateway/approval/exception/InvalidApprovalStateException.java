package com.agentgate.gateway.approval.exception;

/**
 * Thrown when an illegal or expired state transition is attempted on an approval request.
 * Extends IllegalStateException for backward compatibility.
 */
public class InvalidApprovalStateException extends IllegalStateException {
    public InvalidApprovalStateException(String message) {
        super(message);
    }
}
