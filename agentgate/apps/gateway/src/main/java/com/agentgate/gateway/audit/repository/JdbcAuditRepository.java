package com.agentgate.gateway.audit.repository;

import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAuditRepository implements AuditRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private AuditEvent mapAuditEventRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEvent(
                rs.getLong("sequence_number"),
                rs.getTimestamp("event_timestamp").toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                rs.getString("event_type"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("actor_id"),
                rs.getString("tool_name"),
                rs.getString("redacted_arguments_json"),
                rs.getString("policy_rule_id"),
                rs.getString("policy_rule_name"),
                rs.getString("decision"),
                rs.getString("approval_id"),
                rs.getString("outcome"),
                rs.getLong("latency_ms"),
                rs.getString("error_message"),
                rs.getString("prev_record_hash"),
                rs.getString("record_hash")
        );
    }

    private AuditChainState mapChainStateRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditChainState(
                rs.getInt("id"),
                rs.getLong("last_sequence"),
                rs.getString("last_hash")
        );
    }

    @Override
    public AuditChainState getChainStateForUpdate() {
        Optional<AuditChainState> state = jdbcClient.sql("SELECT id, last_sequence, last_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE")
                .query(this::mapChainStateRow)
                .optional();

        if (state.isPresent()) {
            return state.get();
        }

        // Initialize genesis row if absent
        jdbcClient.sql("""
                INSERT INTO audit_chain_state (id, last_sequence, last_hash)
                SELECT 1, 0, '0000000000000000000000000000000000000000000000000000000000000000'
                WHERE NOT EXISTS (SELECT 1 FROM audit_chain_state WHERE id = 1)
                """).update();

        return jdbcClient.sql("SELECT id, last_sequence, last_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE")
                .query(this::mapChainStateRow)
                .single();
    }

    @Override
    public AuditChainState getChainState() {
        Optional<AuditChainState> state = jdbcClient.sql("SELECT id, last_sequence, last_hash FROM audit_chain_state WHERE id = 1")
                .query(this::mapChainStateRow)
                .optional();

        if (state.isPresent()) {
            return state.get();
        }

        jdbcClient.sql("""
                INSERT INTO audit_chain_state (id, last_sequence, last_hash)
                SELECT 1, 0, '0000000000000000000000000000000000000000000000000000000000000000'
                WHERE NOT EXISTS (SELECT 1 FROM audit_chain_state WHERE id = 1)
                """).update();

        return jdbcClient.sql("SELECT id, last_sequence, last_hash FROM audit_chain_state WHERE id = 1")
                .query(this::mapChainStateRow)
                .single();
    }

    @Override
    public void insertAuditEvent(AuditEvent event) {
        jdbcClient.sql("""
                INSERT INTO audit_log (
                    sequence_number, event_timestamp, event_type, agent_id, user_id, actor_id, tool_name,
                    redacted_arguments_json, policy_rule_id, policy_rule_name, decision, approval_id,
                    outcome, latency_ms, error_message, prev_record_hash, record_hash
                ) VALUES (
                    :sequenceNumber, :eventTimestamp, :eventType, :agentId, :userId, :actorId, :toolName,
                    :redactedArgsJson, :policyRuleId, :policyRuleName, :decision, :approvalId,
                    :outcome, :latencyMs, :errorMessage, :prevRecordHash, :recordHash
                )
                """)
                .param("sequenceNumber", event.sequenceNumber())
                .param("eventTimestamp", Timestamp.from(event.eventTimestamp()))
                .param("eventType", event.eventType())
                .param("agentId", event.agentId())
                .param("userId", event.userId())
                .param("actorId", event.actorId())
                .param("toolName", event.toolName())
                .param("redactedArgsJson", event.redactedArgumentsJson())
                .param("policyRuleId", event.policyRuleId())
                .param("policyRuleName", event.policyRuleName())
                .param("decision", event.decision())
                .param("approvalId", event.approvalId())
                .param("outcome", event.outcome())
                .param("latencyMs", event.latencyMs())
                .param("errorMessage", event.errorMessage())
                .param("prevRecordHash", event.prevRecordHash())
                .param("recordHash", event.recordHash())
                .update();
    }

    @Override
    public void updateChainState(long newSequence, String newHash) {
        jdbcClient.sql("UPDATE audit_chain_state SET last_sequence = :newSequence, last_hash = :newHash WHERE id = 1")
                .param("newSequence", newSequence)
                .param("newHash", newHash)
                .update();
    }

    @Override
    public Optional<AuditEvent> findBySequenceNumber(long sequenceNumber) {
        return jdbcClient.sql("SELECT * FROM audit_log WHERE sequence_number = :sequenceNumber")
                .param("sequenceNumber", sequenceNumber)
                .query(this::mapAuditEventRow)
                .optional();
    }

    @Override
    public List<AuditEvent> findAllOrderBySequence() {
        return jdbcClient.sql("SELECT * FROM audit_log ORDER BY sequence_number ASC")
                .query(this::mapAuditEventRow)
                .list();
    }

    @Override
    public List<AuditEvent> findByApprovalId(String approvalId) {
        return jdbcClient.sql("SELECT * FROM audit_log WHERE approval_id = :approvalId ORDER BY sequence_number ASC")
                .param("approvalId", approvalId)
                .query(this::mapAuditEventRow)
                .list();
    }

    @Override
    public long count() {
        return jdbcClient.sql("SELECT COUNT(*) FROM audit_log")
                .query(Long.class)
                .single();
    }

    @Override
    public void resetToGenesis() {
        jdbcClient.sql("DELETE FROM audit_log").update();
        jdbcClient.sql("UPDATE audit_chain_state SET last_sequence = 0, last_hash = '0000000000000000000000000000000000000000000000000000000000000000' WHERE id = 1").update();
        jdbcClient.sql("""
                INSERT INTO audit_chain_state (id, last_sequence, last_hash)
                SELECT 1, 0, '0000000000000000000000000000000000000000000000000000000000000000'
                WHERE NOT EXISTS (SELECT 1 FROM audit_chain_state WHERE id = 1)
                """).update();
    }
}
