package com.insighthub.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 用户与刷新令牌持久化。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByUsername(String username) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        return c != null && c > 0;
    }

    public boolean existsByEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE email = ?", Integer.class, email);
        return c != null && c > 0;
    }

    public void insertUser(String id, String username, String passwordHash, String email, String displayName) {
        jdbcTemplate.update(
                """
                INSERT INTO sys_user (id, username, password_hash, email, display_name, status)
                VALUES (?, ?, ?, ?, ?, 1)
                """,
                id, username, passwordHash, email, displayName);
    }

    public Optional<UserRow> findByUsername(String username) {
        List<UserRow> list = jdbcTemplate.query(
                """
                SELECT id, username, password_hash, email, display_name, status
                FROM sys_user WHERE username = ?
                """,
                (rs, i) -> new UserRow(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getInt("status")),
                username);
        return list.stream().findFirst();
    }

    public Optional<UserRow> findById(String id) {
        List<UserRow> list = jdbcTemplate.query(
                """
                SELECT id, username, password_hash, email, display_name, status
                FROM sys_user WHERE id = ?
                """,
                (rs, i) -> new UserRow(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getInt("status")),
                id);
        return list.stream().findFirst();
    }

    public void touchLastLogin(String userId) {
        jdbcTemplate.update("UPDATE sys_user SET last_login_at = NOW() WHERE id = ?", userId);
    }

    public void insertRefreshToken(String id, String userId, String tokenHash, java.sql.Timestamp expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO sys_refresh_token (id, user_id, token_hash, expires_at, revoked)
                VALUES (?, ?, ?, ?, 0)
                """,
                id, userId, tokenHash, expiresAt);
    }

    public Optional<RefreshTokenRow> findRefreshToken(String tokenHash) {
        List<RefreshTokenRow> list = jdbcTemplate.query(
                """
                SELECT id, user_id, token_hash, expires_at, revoked
                FROM sys_refresh_token WHERE token_hash = ?
                """,
                (rs, i) -> new RefreshTokenRow(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at"),
                        rs.getInt("revoked") == 1),
                tokenHash);
        return list.stream().findFirst();
    }

    public void revokeRefreshToken(String id) {
        jdbcTemplate.update("UPDATE sys_refresh_token SET revoked = 1 WHERE id = ?", id);
    }

    public record UserRow(
            String id,
            String username,
            String passwordHash,
            String email,
            String displayName,
            int status) {
    }

    public record RefreshTokenRow(
            String id,
            String userId,
            String tokenHash,
            java.sql.Timestamp expiresAt,
            boolean revoked) {
    }
}
