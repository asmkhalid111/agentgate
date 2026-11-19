package com.agentgate.gateway.killswitch.repository;

import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL repository for Emergency Kill Switches (FR8).
 * Directly queries the authoritative database state without caching.
 */
@Repository
public class KillSwitchRepository {

    private final JdbcClient jdbcClient;

    public KillSwitchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private KillSwitch mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new KillSwitch(
                rs.getString("id"),
                KillSwitchType.valueOf(rs.getString("type")),
                rs.getString("target"),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        );
    }

    /**
     * Inserts or reactivates a kill switch idempotently.
     */
    public void upsert(KillSwitch switchRecord) {
        String updateSql = """
                UPDATE kill_switches
                SET active = :active,
                    reason = :reason,
                    created_by = :createdBy,
                    updated_at = :updatedAt
                WHERE type = :type AND target = :target
                """;
        int updated = jdbcClient.sql(updateSql)
                .param("active", switchRecord.active())
                .param("reason", switchRecord.reason())
                .param("createdBy", switchRecord.createdBy())
                .param("updatedAt", Timestamp.from(switchRecord.updatedAt()))
                .param("type", switchRecord.type().name())
                .param("target", switchRecord.target())
                .update();

        if (updated == 0) {
            String insertSql = """
                    INSERT INTO kill_switches (id, type, target, active, reason, created_by, created_at, updated_at)
                    VALUES (:id, :type, :target, :active, :reason, :createdBy, :createdAt, :updatedAt)
                    """;
            try {
                jdbcClient.sql(insertSql)
                        .param("id", switchRecord.id())
                        .param("type", switchRecord.type().name())
                        .param("target", switchRecord.target())
                        .param("active", switchRecord.active())
                        .param("reason", switchRecord.reason())
                        .param("createdBy", switchRecord.createdBy())
                        .param("createdAt", Timestamp.from(switchRecord.createdAt()))
                        .param("updatedAt", Timestamp.from(switchRecord.updatedAt()))
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                jdbcClient.sql(updateSql)
                        .param("active", switchRecord.active())
                        .param("reason", switchRecord.reason())
                        .param("createdBy", switchRecord.createdBy())
                        .param("updatedAt", Timestamp.from(switchRecord.updatedAt()))
                        .param("type", switchRecord.type().name())
                        .param("target", switchRecord.target())
                        .update();
            }
        }
    }

    /**
     * Idempotently deactivates an active kill switch.
     */
    public boolean deactivate(KillSwitchType type, String target, Instant updatedAt) {
        String sql = """
                UPDATE kill_switches
                SET active = FALSE, updated_at = :updatedAt
                WHERE type = :type AND target = :target
                """;
        int rows = jdbcClient.sql(sql)
                .param("type", type.name())
                .param("target", target)
                .param("updatedAt", Timestamp.from(updatedAt))
                .update();
        return rows > 0;
    }

    /**
     * Directly queries the authoritative database to find an active kill switch matching
     * the agent, tool, or global write condition.
     */
    public Optional<KillSwitch> findActiveSwitch(String agentId, String toolName, boolean isWrite) {
        String sql = """
                SELECT id, type, target, active, reason, created_by, created_at, updated_at
                FROM kill_switches
                WHERE active = TRUE
                  AND (
                      (type = 'AGENT' AND target = :agentId)
                   OR (type = 'TOOL' AND target = :toolName)
                   OR (type = 'GLOBAL_WRITE' AND :isWrite = TRUE)
                  )
                LIMIT 1
                """;
        return jdbcClient.sql(sql)
                .param("agentId", agentId != null ? agentId : "")
                .param("toolName", toolName != null ? toolName : "")
                .param("isWrite", isWrite)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Finds a kill switch by type and target.
     */
    public Optional<KillSwitch> findByTypeAndTarget(KillSwitchType type, String target) {
        String sql = "SELECT * FROM kill_switches WHERE type = :type AND target = :target";
        return jdbcClient.sql(sql)
                .param("type", type.name())
                .param("target", target)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Lists kill switches with optional active filter and pagination.
     */
    public List<KillSwitch> findAll(Boolean activeOnly, int limit, int offset) {
        if (activeOnly != null) {
            String sql = "SELECT * FROM kill_switches WHERE active = :active ORDER BY updated_at DESC LIMIT :limit OFFSET :offset";
            return jdbcClient.sql(sql)
                    .param("active", activeOnly)
                    .param("limit", limit)
                    .param("offset", offset)
                    .query(this::mapRow)
                    .list();
        } else {
            String sql = "SELECT * FROM kill_switches ORDER BY updated_at DESC LIMIT :limit OFFSET :offset";
            return jdbcClient.sql(sql)
                    .param("limit", limit)
                    .param("offset", offset)
                    .query(this::mapRow)
                    .list();
        }
    }

    /**
     * Counts kill switches with optional active filter.
     */
    public long count(Boolean activeOnly) {
        String sql = activeOnly != null
                ? "SELECT COUNT(*) FROM kill_switches WHERE active = :active"
                : "SELECT COUNT(*) FROM kill_switches";
        var query = jdbcClient.sql(sql);
        if (activeOnly != null) {
            query = query.param("active", activeOnly);
        }
        Long c = query.query(Long.class).single();
        return c != null ? c : 0L;
    }

    /**
     * Purges all kill switches (used primarily for test isolation).
     */
    public void deleteAll() {
        jdbcClient.sql("DELETE FROM kill_switches").update();
    }
}
