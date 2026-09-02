package com.hechang.insighthub.security;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.DocsProperties;

import lombok.RequiredArgsConstructor;

/**
 * Spring Security：JWT 无状态会话。
 *
 * <p>SSE 使用 Servlet 异步派发；SecurityContext 仓库见 {@link SecurityContextConfig}。
 * REST API 仅接受 JWT，不创建 HTTP Session。CSRF 关闭的前提是认证凭据不由浏览器
 * Cookie 自动附带；若未来改用 Cookie，必须重新启用 CSRF 防护。</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] DOC_PATHS = {
            "/doc.html",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/webjars/**",
            "/favicon.ico"
    };

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;
    private final DocsProperties docsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ASYNC 派发从 request-scoped Repository 恢复登录态；它不创建服务端
                // Session，仅用于同一个 SSE 请求的异步 dispatch。
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(false))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    "/api/v1/auth/register",
                                    "/api/v1/auth/login",
                                    "/api/v1/auth/refresh",
                                    "/api/v1/health")
                            .permitAll();
                    // 文档匿名访问由显式配置控制；生产应关闭，避免公开内部接口模型。
                    if (docsProperties.isPublicAccess()) {
                        auth.requestMatchers(DOC_PATHS).permitAll();
                    }
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(), java.util.Map.of(
                                    "code", "UNAUTHORIZED",
                                    "message", "login required",
                                    "details", java.util.Map.of()));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(), java.util.Map.of(
                                    "code", "FORBIDDEN",
                                    "message", "forbidden",
                                    "details", java.util.Map.of()));
                        }))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
