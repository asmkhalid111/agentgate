package com.agentgate.gateway.mcp;

import com.agentgate.gateway.approval.dto.ApprovalResultResponse;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.identity.AgentContextHolder;
import com.agentgate.gateway.identity.AgentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in MCP tool provider for retrieving human approval results (FR4).
 * Strictly read-only; never triggers downstream execution or state changes.
 */
@Component
public class ApprovalResultMcpProvider {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResultMcpProvider.class);

    private final ApprovalService approvalService;
    private final AuditRecorder auditRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalResultMcpProvider(
            ApprovalService approvalService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AuditRecorder auditRecorder) {
        this.approvalService = approvalService;
        this.auditRecorder = auditRecorder;
    }

    public ApprovalResultMcpProvider(ApprovalService approvalService) {
        this(approvalService, null);
    }

    /**
     * Retrieves the status and sanitized outcome of an approval request for the authenticated calling agent.
     * Enforces ownership strictly via verified JWT authentication; caller-supplied agent identities are ignored.
     *
     * @param approvalId identifier of the approval request (e.g. appr-...)
     * @return structured map containing approval status, execution state, and sanitized result
     */
    @Tool(
            name = "get_approval_result",
            description = "Retrieves the status and sanitized execution result of a human approval request for the authenticated calling agent."
    )
    public Map<String, Object> getApprovalResult(
            @ToolParam(description = "The approval request identifier (e.g. appr-...)") String approvalId) {

        AgentIdentity currentAgent = AgentContextHolder.getCurrentAgent()
                .orElseThrow(() -> new AccessDeniedException("Unauthorized: No authenticated agent identity found in context"));

        if (approvalId == null || approvalId.isBlank()) {
            return Map.of(
                    "approvalId", "",
                    "status", "NOT_FOUND",
                    "message", "Approval request not found"
            );
        }

        String sanitizedId = approvalId.trim();
        log.info("Agent '{}' requested approval result for '{}'", currentAgent.clientId(), sanitizedId);

        Optional<ApprovalResultResponse> resultOpt = approvalService.getApprovalResult(sanitizedId, currentAgent.clientId());
        if (auditRecorder != null) {
            auditRecorder.recordBuiltinToolExecution(
                    currentAgent.clientId(),
                    null,
                    "get_approval_result",
                    sanitizedId,
                    0L,
                    resultOpt.isPresent() ? "SUCCESS" : "NOT_FOUND",
                    null
            );
        }
        if (resultOpt.isEmpty()) {
            // Uniform non-disclosing response for both non-existent and unauthorized approval IDs
            return Map.of(
                    "approvalId", sanitizedId,
                    "status", "NOT_FOUND",
                    "message", "Approval request not found"
            );
        }

        return toResponseMap(resultOpt.get());
    }

    private Map<String, Object> toResponseMap(ApprovalResultResponse res) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approvalId", res.approvalId());
        map.put("toolName", res.toolName());
        map.put("status", res.status());
        if (res.executionState() != null) {
            map.put("executionState", res.executionState());
        }
        if (res.result() != null) {
            map.put("result", res.result());
        }
        if (res.error() != null) {
            map.put("error", res.error());
        }
        if (res.message() != null) {
            map.put("message", res.message());
        }
        if (res.createdAt() != null) {
            map.put("createdAt", res.createdAt().toString());
        }
        if (res.expiresAt() != null) {
            map.put("expiresAt", res.expiresAt().toString());
        }
        if (res.decidedAt() != null) {
            map.put("decidedAt", res.decidedAt().toString());
        }
        if (res.executedAt() != null) {
            map.put("executedAt", res.executedAt().toString());
        }
        return map;
    }
}
