package com.agentgate.gateway.approval.domain;

/**
 * State of a human approval request (FR4).
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED
}
