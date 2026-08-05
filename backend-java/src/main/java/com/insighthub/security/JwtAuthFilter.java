package com.insighthub.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 从 Authorization: Bearer 解析 JWT 并注入 SecurityContext。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            try {
                Claims claims = jwtService.parseClaims(token);
                String userId = claims.getSubject();
                UserPrincipal principal = loadUser(userId);
                if (principal != null && principal.isEnabled()) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // 无效 token：保持未认证，由后续链路返回 401
            }
        }
        filterChain.doFilter(request, response);
    }

    private UserPrincipal loadUser(String userId) {
        // JWT 路径只需身份与启用状态，不加载 password_hash
        var list = jdbcTemplate.query(
                """
                SELECT id, username, status
                FROM sys_user WHERE id = ?
                """,
                (rs, rowNum) -> new UserPrincipal(
                        rs.getString("id"),
                        rs.getString("username"),
                        "",
                        rs.getInt("status") == 1),
                userId);
        return list.isEmpty() ? null : list.get(0);
    }
}
