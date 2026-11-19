package com.agentgate.gateway.audit.repository;

import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.dto.AuditSearchCriteria;
import com.agentgate.gateway.audit.dto.AuditSearchResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public AuditSearchResult search(AuditSearchCriteria criteria) {
        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (criteria.agentId() != null) {
            whereClause.append(" AND agent_id = :agentId");
            params.put("agentId", criteria.agentId());
        }
        if (criteria.toolName() != null) {
            whereClause.append(" AND tool_name = :toolName");
            params.put("toolName", criteria.toolName());
        }
        if (criteria.approvalId() != null) {
            whereClause.append(" AND approval_id = :approvalId");
            params.put("approvalId", criteria.approvalId());
        }
        if (criteria.outcome() != null) {
            whereClause.append(" AND outcome = :outcome");
            params.put("outcome", criteria.outcome());
        }
        if (criteria.decision() != null) {
            whereClause.append(" AND decision = :decision");
            params.put("decision", criteria.decision());
        }
        if (criteria.from() != null) {
            whereClause.append(" AND event_timestamp >= :fromTimestamp");
            params.put("fromTimestamp", Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            whereClause.append(" AND event_timestamp <= :toTimestamp");
            params.put("toTimestamp", Timestamp.from(criteria.to()));
        }

        String countSql = "SELECT COUNT(*) FROM audit_log" + whereClause;
        var countQuery = jdbcClient.sql(countSql);
        params.forEach(countQuery::param);
        long totalElements = countQuery.query(Long.class).single();

        if (totalElements == 0) {
            return AuditSearchResult.of(List.of(), criteria.page(), criteria.size(), 0);
        }

        int offset = criteria.page() * criteria.size();
        String dataSql = "SELECT * FROM audit_log" + whereClause + " ORDER BY sequence_number ASC LIMIT :limit OFFSET :offset";
        var dataQuery = jdbcClient.sql(dataSql);
        params.forEach(dataQuery::param);
        dataQuery.param("limit", criteria.size());
        dataQuery.param("offset", offset);

        List<AuditEvent> content = dataQuery.query(this::mapAuditEventRow).list();
        return AuditSearchResult.of(content, criteria.page(), criteria.size(), totalElements);
    }

    @Override
    public List<AuditEvent> export(AuditSearchCriteria criteria, int limit) {
        int boundedLimit = Math.min(Math.max(1, limit), 1000);
        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (criteria.agentId() != null) {
            whereClause.append(" AND agent_id = :agentId");
            params.put("agentId", criteria.agentId());
        }
        if (criteria.toolName() != null) {
            whereClause.append(" AND tool_name = :toolName");
            params.put("toolName", criteria.toolName());
        }
        if (criteria.approvalId() != null) {
            whereClause.append(" AND approval_id = :approvalId");
            params.put("approvalId", criteria.approvalId());
        }
        if (criteria.outcome() != null) {
            whereClause.append(" AND outcome = :outcome");
            params.put("outcome", criteria.outcome());
        }
        if (criteria.decision() != null) {
            whereClause.append(" AND decision = :decision");
            params.put("decision", criteria.decision());
        }
        if (criteria.from() != null) {
            whereClause.append(" AND event_timestamp >= :fromTimestamp");
            params.put("fromTimestamp", Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            whereClause.append(" AND event_timestamp <= :toTimestamp");
            params.put("toTimestamp", Timestamp.from(criteria.to()));
        }

        String dataSql = "SELECT * FROM audit_log" + whereClause + " ORDER BY sequence_number ASC LIMIT :limit";
        var dataQuery = jdbcClient.sql(dataSql);
        params.forEach(dataQuery::param);
        dataQuery.param("limit", boundedLimit);

        return dataQuery.query(this::mapAuditEventRow).list();
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
