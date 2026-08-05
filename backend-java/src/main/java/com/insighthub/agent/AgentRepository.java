package com.insighthub.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Agent 定义表访问。
 */
@Repository
public class AgentRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(
            String id,
            String workspaceId,
            String name,
            String agentType,
            String runtime,
            String promptVersion,
            String systemPrompt,
            boolean enabled,
            int version) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_definition
                  (id, workspace_id, name, agent_type, runtime, prompt_version, system_prompt, enabled, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, workspaceId, name, agentType, runtime, promptVersion, systemPrompt, enabled ? 1 : 0, version);
    }

    public List<AgentRow> listByWorkspace(String workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT id, workspace_id, name, agent_type, runtime, prompt_version, system_prompt, enabled, version
                FROM agent_definition
                WHERE workspace_id = ?
                ORDER BY agent_type, name
                """,
                (rs, i) -> mapRow(rs),
                workspaceId);
    }

    public Optional<AgentRow> findByIdAndWorkspace(String agentId, String workspaceId) {
        List<AgentRow> list = jdbcTemplate.query(
                """
                SELECT id, workspace_id, name, agent_type, runtime, prompt_version, system_prompt, enabled, version
                FROM agent_definition
                WHERE id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapRow(rs),
                agentId, workspaceId);
        return list.stream().findFirst();
    }

    public void update(
            String agentId,
            String workspaceId,
            String name,
            String systemPrompt,
            String promptVersion) {
        jdbcTemplate.update(
                """
                UPDATE agent_definition
                SET name = ?, system_prompt = ?, prompt_version = ?, version = version + 1, updated_at = NOW()
                WHERE id = ? AND workspace_id = ?
                """,
                name, systemPrompt, promptVersion, agentId, workspaceId);
    }

    public void setEnabled(String agentId, String workspaceId, boolean enabled) {
        jdbcTemplate.update(
                """
                UPDATE agent_definition
                SET enabled = ?, updated_at = NOW()
                WHERE id = ? AND workspace_id = ?
                """,
                enabled ? 1 : 0, agentId, workspaceId);
    }

    public boolean existsType(String workspaceId, String agentType) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_definition WHERE workspace_id = ? AND agent_type = ?",
                Integer.class, workspaceId, agentType);
        return c != null && c > 0;
    }

    private static AgentRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("name"),
                rs.getString("agent_type"),
                rs.getString("runtime"),
                rs.getString("prompt_version"),
                rs.getString("system_prompt"),
                rs.getInt("enabled") == 1,
                rs.getInt("version"));
    }

    public record AgentRow(
            String id,
            String workspaceId,
            String name,
            String agentType,
            String runtime,
            String promptVersion,
            String systemPrompt,
            boolean enabled,
            int version) {
    }
}
