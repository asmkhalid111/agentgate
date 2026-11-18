package com.agentgate.gateway.policy.repository;

import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.domain.StructuredCondition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class PolicyRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PolicyRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    private PolicyRule mapRow(ResultSet rs, int rowNum) throws SQLException {
        String conditionsJson = rs.getString("conditions_json");
        List<StructuredCondition> conditions;
        try {
            if (conditionsJson != null && !conditionsJson.isBlank()) {
                conditions = objectMapper.readValue(conditionsJson, new TypeReference<>() {});
            } else {
                conditions = Collections.emptyList();
            }
        } catch (Exception e) {
            conditions = Collections.emptyList();
        }

        return new PolicyRule(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("agent_pattern"),
                rs.getString("tool_pattern"),
                conditions,
                PolicyAction.valueOf(rs.getString("action")),
                rs.getBoolean("dry_run"),
                rs.getInt("priority"),
                rs.getInt("version"),
                rs.getBoolean("enabled")
        );
    }

    public List<PolicyRule> findAll() {
        return jdbcClient.sql("SELECT * FROM policies WHERE enabled = true ORDER BY priority ASC, name ASC")
                .query(this::mapRow)
                .list();
    }

    public List<PolicyRule> findAllIncludeDisabled() {
        return jdbcClient.sql("SELECT * FROM policies ORDER BY priority ASC, name ASC")
                .query(this::mapRow)
                .list();
    }

    public Optional<PolicyRule> findById(String id) {
        return jdbcClient.sql("SELECT * FROM policies WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public void save(PolicyRule rule) {
        deleteById(rule.id());
        String conditionsJson;
        try {
            conditionsJson = objectMapper.writeValueAsString(rule.conditions());
        } catch (Exception e) {
            conditionsJson = "[]";
        }

        String sql = """
                INSERT INTO policies (id, name, agent_pattern, tool_pattern, conditions_json, action, dry_run, priority, version, enabled, updated_at)
                VALUES (:id, :name, :agentPattern, :toolPattern, :conditionsJson, :action, :dryRun, :priority, :version, :enabled, CURRENT_TIMESTAMP)
                """;

        jdbcClient.sql(sql)
                .param("id", rule.id())
                .param("name", rule.name())
                .param("agentPattern", rule.agentPattern())
                .param("toolPattern", rule.toolPattern())
                .param("conditionsJson", conditionsJson)
                .param("action", rule.action().name())
                .param("dryRun", rule.dryRun())
                .param("priority", rule.priority())
                .param("version", rule.version())
                .param("enabled", rule.enabled())
                .update();
    }

    public void deleteById(String id) {
        jdbcClient.sql("DELETE FROM policies WHERE id = :id")
                .param("id", id)
                .update();
    }
}
