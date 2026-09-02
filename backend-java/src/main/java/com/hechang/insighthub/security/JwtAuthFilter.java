package com.hechang.insighthub.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hechang.insighthub.mapper.SysUserMapper;
import com.hechang.insighthub.model.entity.SysUser;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 从 Authorization: Bearer 解析 JWT 并注入 SecurityContext。
 * SSE 异步派发会再次进入 Filter 链；必须在 ASYNC/ERROR dispatch 上重新认证，
 * 否则会变成 Anonymous 并触发 AccessDenied（响应已 committed 时还会连环报错）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;
    private final SecurityContextRepository securityContextRepository;

    /**
     * 允许在 Servlet ASYNC 派发时执行本 Filter（SseEmitter 心跳/推送会触发）。
     *
     * @return false 表示不跳过 async dispatch
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 允许在 ERROR 派发时执行本 Filter，避免 /error 再次因未认证被拒。
     *
     * @return false 表示不跳过 error dispatch
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = null;
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7).trim();
            } else if (isSseEventsPath(request)) {
                // 历史 EventSource 兼容路径。query token 可能进入代理日志、浏览器历史和
                // Referrer；当前 fetch-based SSE 已能携带 Authorization，应尽快移除该分支。
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
                        // 写入 Holder，并立刻落到 request attribute，供 SSE ASYNC 派发恢复
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(auth);
                        SecurityContextHolder.setContext(context);
                        securityContextRepository.saveContext(context, request, response);
                    }
                } catch (Exception ignored) {
                    // 不向客户端区分签名、过期或用户不存在，避免泄露认证细节；保持未认证，
                    // 由统一入口返回 401。日志若补充诊断也不得记录 token 本身。
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
