package com.agentgate.gateway.approval.event;

import com.agentgate.gateway.approval.domain.ApprovalRequest;

/**
 * Domain event published when an approval request is successfully approved by an authenticated human.
 */
public record ApprovalApprovedEvent(ApprovalRequest request) {
}
