package com.hechang.insighthub.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hechang.insighthub.mapper.SysUserMapper;
import com.hechang.insighthub.model.entity.SysUser;

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
    private final SysUserMapper sysUserMapper;

    public JwtAuthFilter(JwtService jwtService, SysUserMapper sysUserMapper) {
        this.jwtService = jwtService;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = null;
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7).trim();
            } else if (isSseEventsPath(request)) {
                // 仅 SSE /events 允许 ?access_token=（EventSource 不便带 Header）
                String q = request.getParameter("access_token");
                if (q != null && !q.isBlank()) {
                    token = q.trim();
                }
            }
            if (token != null && !token.isEmpty()) {
                try {
                    Claims claims = jwtService.parseClaims(token);
                    String userId = claims.getSubject();
                    UserPrincipal principal = loadUser(userId);
                    if (principal != null && principal.isEnabled()) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        principal, null, principal.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (Exception ignored) {
                    // 无效 token：保持未认证，由后续链路返回 401
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 仅研究任务事件 SSE 路径可使用 query token。 */
    private static boolean isSseEventsPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return uri.contains("/research/tasks/") && uri.endsWith("/events");
    }

    /** JWT 路径只需身份与启用状态，不加载 password_hash */
    private UserPrincipal loadUser(String userId) {
        SysUser user = sysUserMapper.selectOneById(userId);
        if (user == null) {
            return null;
        }
        boolean enabled = user.getStatus() != null && user.getStatus() == 1;
        return new UserPrincipal(user.getId(), user.getUsername(), "", enabled);
    }
}
