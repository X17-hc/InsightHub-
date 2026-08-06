package com.hechang.insighthub.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hechang.insighthub.exception.BusinessException;

/**
 * 从 SecurityContext 读取当前用户。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UserPrincipal requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw BusinessException.unauthorized("login required");
        }
        return principal;
    }

    public static String requireUserId() {
        return requireCurrentUser().getUserId();
    }
}
