package com.agentgate.gateway.approval.repository;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.domain.ExecutionState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL JDBC repository for human approval requests (FR4).
 * Enforces atomic state transitions and timeout constraints directly in SQL.
 */
@Repository
public class ApprovalRequestRepository {

    private final JdbcClient jdbcClient;

    public ApprovalRequestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private ApprovalRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
        String execStateStr = rs.getString("execution_state");
        ExecutionState execState = execStateStr != null ? ExecutionState.valueOf(execStateStr) : ExecutionState.NOT_DISPATCHED;

        return new ApprovalRequest(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("tool_name"),
                rs.getString("policy_rule_id"),
                rs.getString("original_arguments_json"),
                rs.getString("approved_arguments_json"),
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant() : null,
                rs.getString("decided_by"),
                rs.getString("decision_reason"),
                execState,
                rs.getTimestamp("executed_at") != null ? rs.getTimestamp("executed_at").toInstant() : null,
                rs.getString("execution_result_json"),
                rs.getString("execution_error")
        );
    }

    public void save(ApprovalRequest request) {
        jdbcClient.sql("""
                INSERT INTO approval_requests (
                    id, agent_id, user_id, tool_name, policy_rule_id,
                    original_arguments_json, approved_arguments_json, status,
                    created_at, expires_at, decided_at, decided_by, decision_reason,
                    execution_state, executed_at, execution_result_json, execution_error
                ) VALUES (
                    :id, :agentId, :userId, :toolName, :policyRuleId,
                    :originalArgs, :approvedArgs, :status,
                    :createdAt, :expiresAt, :decidedAt, :decidedBy, :reason,
                    :executionState, :executedAt, :executionResultJson, :executionError
                )
                """)
                .param("id", request.id())
                .param("agentId", request.agentId())
                .param("userId", request.userId())
                .param("toolName", request.toolName())
                .param("policyRuleId", request.policyRuleId())
                .param("originalArgs", request.originalArgumentsJson())
                .param("approvedArgs", request.approvedArgumentsJson())
                .param("status", request.status().name())
                .param("createdAt", request.createdAt() != null ? Timestamp.from(request.createdAt()) : null)
                .param("expiresAt", request.expiresAt() != null ? Timestamp.from(request.expiresAt()) : null)
                .param("decidedAt", request.decidedAt() != null ? Timestamp.from(request.decidedAt()) : null)
                .param("decidedBy", request.decidedBy())
                .param("reason", request.decisionReason())
                .param("executionState", request.executionState() != null ? request.executionState().name() : ExecutionState.NOT_DISPATCHED.name())
                .param("executedAt", request.executedAt() != null ? Timestamp.from(request.executedAt()) : null)
                .param("executionResultJson", request.executionResultJson())
                .param("executionError", request.executionError())
                .update();
    }

    public Optional<ApprovalRequest> findById(String id) {
        return jdbcClient.sql("SELECT * FROM approval_requests WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        return jdbcClient.sql("SELECT * FROM approval_requests WHERE status = :status ORDER BY created_at ASC")
                .param("status", status.name())
                .query(this::mapRow)
                .list();
    }

    public List<ApprovalRequest> findAll() {
        return jdbcClient.sql("SELECT * FROM approval_requests ORDER BY created_at ASC, id ASC")
                .query(this::mapRow)
                .list();
    }

    public List<ApprovalRequest> findPaged(ApprovalStatus status, int offset, int limit) {
        if (status != null) {
            return jdbcClient.sql("""
                    SELECT * FROM approval_requests
                    WHERE status = :status
                    ORDER BY created_at ASC, id ASC
                    LIMIT :limit OFFSET :offset
                    """)
                    .param("status", status.name())
                    .param("limit", limit)
                    .param("offset", offset)
                    .query(this::mapRow)
                    .list();
        } else {
            return jdbcClient.sql("""
                    SELECT * FROM approval_requests
                    ORDER BY created_at ASC, id ASC
                    LIMIT :limit OFFSET :offset
                    """)
                    .param("limit", limit)
                    .param("offset", offset)
                    .query(this::mapRow)
                    .list();
        }
    }

    public long countByStatus(ApprovalStatus status) {
        if (status != null) {
            return jdbcClient.sql("SELECT COUNT(*) FROM approval_requests WHERE status = :status")
                    .param("status", status.name())
                    .query(Long.class)
                    .single();
        } else {
            return jdbcClient.sql("SELECT COUNT(*) FROM approval_requests")
                    .query(Long.class)
                    .single();
        }
    }

    /**
     * Atomically transitions a request from PENDING to APPROVED.
     * Enforces status = 'PENDING' AND expires_at > :now in SQL (Reviewer Correction A).
     */
    public int transitionToApproved(String id, String approvedArgsJson, String decidedBy, Instant decidedAt, Instant now) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'APPROVED',
                    approved_arguments_json = COALESCE(:approvedArgs, original_arguments_json),
                    decided_at = :decidedAt,
                    decided_by = :decidedBy
                WHERE id = :id AND status = 'PENDING' AND expires_at > :now
                """)
                .param("id", id)
                .param("approvedArgs", approvedArgsJson)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("decidedBy", decidedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Atomically transitions a request from PENDING to REJECTED.
     * Enforces status = 'PENDING' AND expires_at > :now in SQL (Reviewer Correction A).
     */
    public int transitionToRejected(String id, String decidedBy, String reason, Instant decidedAt, Instant now) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'REJECTED',
                    decided_at = :decidedAt,
                    decided_by = :decidedBy,
                    decision_reason = :reason
                WHERE id = :id AND status = 'PENDING' AND expires_at > :now
                """)
                .param("id", id)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("decidedBy", decidedBy)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Atomically transitions a request from PENDING to CANCELLED.
     */
    public int transitionToCancelled(String id, String cancelledBy, String reason, Instant decidedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'CANCELLED',
                    decided_at = :decidedAt,
                    decided_by = :cancelledBy,
                    decision_reason = :reason
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .update();
    }

    /**
     * Atomically cancels all PENDING approval requests originating from a specific agent.
     */
    public int cancelPendingByAgent(String agentId, String cancelledBy, String reason, Instant decidedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'CANCELLED',
                    decided_at = :decidedAt,
                    decided_by = :cancelledBy,
                    decision_reason = :reason
                WHERE status = 'PENDING' AND agent_id = :agentId
                """)
                .param("agentId", agentId)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .update();
    }

    /**
     * Atomically cancels all PENDING approval requests targeting a specific tool.
     */
    public int cancelPendingByTool(String toolName, String cancelledBy, String reason, Instant decidedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'CANCELLED',
                    decided_at = :decidedAt,
                    decided_by = :cancelledBy,
                    decision_reason = :reason
                WHERE status = 'PENDING' AND tool_name = :toolName
                """)
                .param("toolName", toolName)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .update();
    }

    /**
     * Atomically cancels all PENDING approval requests for mutating write tools.
     * Tools with GET and HEAD methods are preserved; all other tools (and unclassified tools) are cancelled.
     */
    public int cancelPendingWriteOperations(String cancelledBy, String reason, Instant decidedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'CANCELLED',
                    decided_at = :decidedAt,
                    decided_by = :cancelledBy,
                    decision_reason = :reason
                WHERE status = 'PENDING'
                  AND tool_name NOT IN (
                      SELECT name FROM registered_tools
                      WHERE UPPER(TRIM(http_method)) IN ('GET', 'HEAD')
                  )
                """)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .update();
    }

    /**
     * Atomically transitions a single overdue request from PENDING to EXPIRED.
     */
    public int transitionToExpired(String id, Instant now) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'EXPIRED',
                    decided_at = :now,
                    decided_by = 'SYSTEM',
                    decision_reason = 'Approval request expired after timeout'
                WHERE id = :id AND status = 'PENDING' AND expires_at <= :now
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Bulk-expires all overdue PENDING approval requests.
     */
    public int expirePendingRequests(Instant now) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET status = 'EXPIRED',
                    decided_at = :now,
                    decided_by = 'SYSTEM',
                    decision_reason = 'Approval request expired after timeout'
                WHERE status = 'PENDING' AND expires_at <= :now
                """)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Atomically claims an approved request for tool execution.
     * CAS update: requires status = 'APPROVED' AND execution_state = 'NOT_DISPATCHED'.
     *
     * @param id approval request identifier
     * @param now timestamp of claim
     * @return number of rows updated (1 if successfully claimed, 0 if already claimed or ineligible)
     */
    public int claimExecution(String id, Instant now) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET execution_state = 'DISPATCHED',
                    executed_at = :now
                WHERE id = :id AND status = 'APPROVED' AND execution_state = 'NOT_DISPATCHED'
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Records successful tool execution outcome.
     */
    public int recordExecutionSuccess(String id, String resultJson, Instant executedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET execution_state = 'COMPLETED',
                    executed_at = :executedAt,
                    execution_result_json = :resultJson,
                    execution_error = NULL
                WHERE id = :id AND execution_state = 'DISPATCHED'
                """)
                .param("id", id)
                .param("executedAt", Timestamp.from(executedAt))
                .param("resultJson", resultJson)
                .update();
    }

    /**
     * Records failed tool execution outcome (pre-dispatch check or downstream tool error).
     */
    public int recordExecutionFailure(String id, String error, Instant executedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET execution_state = 'FAILED',
                    executed_at = :executedAt,
                    execution_error = :error
                WHERE id = :id AND execution_state = 'DISPATCHED'
                """)
                .param("id", id)
                .param("executedAt", Timestamp.from(executedAt))
                .param("error", error)
                .update();
    }

    /**
     * Records indeterminate tool execution outcome (e.g. timeout, network disconnect).
     * Never automatically retried.
     */
    public int recordExecutionIndeterminate(String id, String error, Instant executedAt) {
        return jdbcClient.sql("""
                UPDATE approval_requests
                SET execution_state = 'INDETERMINATE',
                    executed_at = :executedAt,
                    execution_error = :error
                WHERE id = :id AND execution_state = 'DISPATCHED'
                """)
                .param("id", id)
                .param("executedAt", Timestamp.from(executedAt))
                .param("error", error)
                .update();
    }

    /**
     * Finds approved requests that have not yet been dispatched for execution.
     * Only returns requests in APPROVED status with execution_state = 'NOT_DISPATCHED'.
     * Requests in DISPATCHED, COMPLETED, FAILED, or INDETERMINATE are never returned.
     *
     * @param limit maximum records to return
     * @return list of undispatched approved requests
     */
    public List<ApprovalRequest> findUndispatchedApproved(int limit) {
        return jdbcClient.sql("""
                SELECT * FROM approval_requests
                WHERE status = 'APPROVED' AND execution_state = 'NOT_DISPATCHED'
                ORDER BY decided_at ASC
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /**
     * Deletes all approval requests. Intended for test isolation.
     */
    public void deleteAll() {
        jdbcClient.sql("DELETE FROM approval_requests").update();
    }
}
