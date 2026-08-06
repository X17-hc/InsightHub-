package com.hechang.insighthub.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.hechang.insighthub.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Access Token 签发与解析。
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;
    private final SecretKey key;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        byte[] bytes = jwtProperties.getSecret() == null
                ? new byte[0]
                : jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        // 拒绝短密钥，避免用可预测填充串签发 JWT
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "insighthub.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes (UTF-8); set JWT_SECRET to a strong value");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 生成 Access Token。
     *
     * @param userId   用户 ID（写入 sub）
     * @param username 用户名（claim）
     * @return 已签名的 Access Token
     */
    public String createAccessToken(String userId, String username) {
        long now = System.currentTimeMillis();
        long exp = now + jwtProperties.getAccessExpireMinutes() * 60_000L;
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 Access Token。
     *
     * @param token Bearer 中的 JWT
     * @return Claims（含 sub=userId）
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
