package com.hechang.insighthub.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 独立提供 SecurityContextRepository，避免与 {@link SecurityConfig}/{@link JwtAuthFilter} 循环依赖。
 *
 * <p>环原先为：JwtAuthFilter → SecurityContextRepository(@Bean in SecurityConfig) → SecurityConfig → JwtAuthFilter。
 */
@Configuration
public class SecurityContextConfig {

    /**
     * 将 SecurityContext 挂在 request attribute 上，供 SSE ASYNC/ERROR 派发恢复（无 Session）。
     *
     * @return SecurityContext 仓库
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new RequestAttributeSecurityContextRepository();
    }
}
