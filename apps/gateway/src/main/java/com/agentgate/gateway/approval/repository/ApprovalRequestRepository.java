package com.agentgate.gateway.approval.repository;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
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
                rs.getString("decision_reason")
        );
    }

    public void save(ApprovalRequest request) {
        jdbcClient.sql("""
                INSERT INTO approval_requests (
                    id, agent_id, user_id, tool_name, policy_rule_id,
                    original_arguments_json, approved_arguments_json, status,
                    created_at, expires_at, decided_at, decided_by, decision_reason
                ) VALUES (
                    :id, :agentId, :userId, :toolName, :policyRuleId,
                    :originalArgs, :approvedArgs, :status,
                    :createdAt, :expiresAt, :decidedAt, :decidedBy, :reason
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
}
