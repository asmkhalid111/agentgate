package com.agentgate.gateway.registry.repository;

import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.registry.domain.RiskLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RegisteredToolRepository {

    private final JdbcClient jdbcClient;

    public RegisteredToolRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private RegisteredTool mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RegisteredTool(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("input_schema"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getString("owning_team"),
                rs.getString("endpoint_url"),
                ProtocolType.valueOf(rs.getString("protocol_type")),
                rs.getString("http_method"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now(),
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : Instant.now()
        );
    }

    public List<RegisteredTool> findAll() {
        return jdbcClient.sql("SELECT * FROM registered_tools ORDER BY name ASC")
                .query(this::mapRow)
                .list();
    }

    public Optional<RegisteredTool> findById(String id) {
        return jdbcClient.sql("SELECT * FROM registered_tools WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public Optional<RegisteredTool> findByName(String name) {
        return jdbcClient.sql("SELECT * FROM registered_tools WHERE name = :name")
                .param("name", name)
                .query(this::mapRow)
                .optional();
    }

    public List<RegisteredTool> findPermittedToolsForAgent(String agentId) {
        String sql = """
                SELECT t.* FROM registered_tools t
                JOIN tool_agent_permissions p ON t.name = p.tool_name
                WHERE p.agent_id = :agentId AND t.enabled = true
                ORDER BY t.name ASC
                """;
        return jdbcClient.sql(sql)
                .param("agentId", agentId)
                .query(this::mapRow)
                .list();
    }

    public boolean isAgentPermitted(String toolName, String agentId) {
        String sql = "SELECT COUNT(*) FROM tool_agent_permissions WHERE tool_name = :toolName AND agent_id = :agentId";
        Integer count = jdbcClient.sql(sql)
                .param("toolName", toolName)
                .param("agentId", agentId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public void save(RegisteredTool tool) {
        deleteByName(tool.name());
        String sql = """
                INSERT INTO registered_tools (id, name, description, input_schema, risk_level, owning_team, endpoint_url, protocol_type, http_method, enabled, created_at, updated_at)
                VALUES (:id, :name, :description, :inputSchema, :riskLevel, :owningTeam, :endpointUrl, :protocolType, :httpMethod, :enabled, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        jdbcClient.sql(sql)
                .param("id", tool.id())
                .param("name", tool.name())
                .param("description", tool.description())
                .param("inputSchema", tool.inputSchema())
                .param("riskLevel", tool.riskLevel().name())
                .param("owningTeam", tool.owningTeam())
                .param("endpointUrl", tool.endpointUrl())
                .param("protocolType", tool.protocolType().name())
                .param("httpMethod", tool.httpMethod())
                .param("enabled", tool.enabled())
                .update();
    }

    public void deleteById(String id) {
        jdbcClient.sql("DELETE FROM registered_tools WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void deleteByName(String name) {
        jdbcClient.sql("DELETE FROM registered_tools WHERE name = :name")
                .param("name", name)
                .update();
    }

    public void addPermission(String toolName, String agentId) {
        removePermission(toolName, agentId);
        String sql = "INSERT INTO tool_agent_permissions (tool_name, agent_id) VALUES (:toolName, :agentId)";
        jdbcClient.sql(sql)
                .param("toolName", toolName)
                .param("agentId", agentId)
                .update();
    }

    public void removePermission(String toolName, String agentId) {
        String sql = "DELETE FROM tool_agent_permissions WHERE tool_name = :toolName AND agent_id = :agentId";
        jdbcClient.sql(sql)
                .param("toolName", toolName)
                .param("agentId", agentId)
                .update();
    }

    public List<String> getPermittedAgents(String toolName) {
        return jdbcClient.sql("SELECT agent_id FROM tool_agent_permissions WHERE tool_name = :toolName")
                .param("toolName", toolName)
                .query(String.class)
                .list();
    }
}
