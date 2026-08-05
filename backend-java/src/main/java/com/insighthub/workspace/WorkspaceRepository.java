package com.insighthub.workspace;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 工作空间与成员 JDBC 访问。
 */
@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertWorkspace(String id, String name, String description, String ownerId) {
        jdbcTemplate.update(
                """
                INSERT INTO workspace (id, name, description, owner_id, max_concurrent_tasks, monthly_token_quota, status)
                VALUES (?, ?, ?, ?, 3, 1000000, 1)
                """,
                id, name, description, ownerId);
    }

    public void insertMember(String id, String workspaceId, String userId, String role) {
        jdbcTemplate.update(
                """
                INSERT INTO workspace_member (id, workspace_id, user_id, role)
                VALUES (?, ?, ?, ?)
                """,
                id, workspaceId, userId, role);
    }

    public Optional<WorkspaceRow> findById(String id) {
        List<WorkspaceRow> list = jdbcTemplate.query(
                """
                SELECT id, name, description, owner_id, status
                FROM workspace WHERE id = ?
                """,
                (rs, i) -> new WorkspaceRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("owner_id"),
                        rs.getInt("status")),
                id);
        return list.stream().findFirst();
    }

    public List<WorkspaceRow> listByUser(String userId) {
        return jdbcTemplate.query(
                """
                SELECT w.id, w.name, w.description, w.owner_id, w.status
                FROM workspace w
                INNER JOIN workspace_member m ON m.workspace_id = w.id
                WHERE m.user_id = ? AND w.status = 1
                ORDER BY w.created_at DESC
                """,
                (rs, i) -> new WorkspaceRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("owner_id"),
                        rs.getInt("status")),
                userId);
    }

    public Optional<String> findMemberRole(String workspaceId, String userId) {
        List<String> list = jdbcTemplate.query(
                """
                SELECT role FROM workspace_member
                WHERE workspace_id = ? AND user_id = ?
                """,
                (rs, i) -> rs.getString("role"),
                workspaceId, userId);
        return list.stream().findFirst();
    }

    public List<MemberRow> listMembers(String workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT m.id, m.workspace_id, m.user_id, m.role, u.username, u.display_name
                FROM workspace_member m
                INNER JOIN sys_user u ON u.id = m.user_id
                WHERE m.workspace_id = ?
                ORDER BY m.joined_at
                """,
                (rs, i) -> new MemberRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("user_id"),
                        rs.getString("role"),
                        rs.getString("username"),
                        rs.getString("display_name")),
                workspaceId);
    }

    public boolean memberExists(String workspaceId, String userId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspaceId, userId);
        return c != null && c > 0;
    }

    public void deleteMember(String workspaceId, String userId) {
        jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspaceId, userId);
    }

    public int countOwners(String workspaceId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND role = 'OWNER'",
                Integer.class, workspaceId);
        return c == null ? 0 : c;
    }

    /**
     * 工作空间最大并发任务数，至少为 1。
     */
    public int getMaxConcurrentTasks(String workspaceId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT max_concurrent_tasks FROM workspace WHERE id = ?",
                Integer.class,
                workspaceId);
        return v == null || v < 1 ? 1 : v;
    }

    public record WorkspaceRow(String id, String name, String description, String ownerId, int status) {
    }

    public record MemberRow(
            String id,
            String workspaceId,
            String userId,
            String role,
            String username,
            String displayName) {
    }
}
