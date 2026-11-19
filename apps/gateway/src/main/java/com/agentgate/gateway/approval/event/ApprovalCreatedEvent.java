package com.agentgate.gateway.approval.event;

import com.agentgate.gateway.approval.domain.ApprovalRequest;

/**
 * Domain event published when a new approval request is created and persisted in PENDING status.
 */
public record ApprovalCreatedEvent(ApprovalRequest request) {
}
