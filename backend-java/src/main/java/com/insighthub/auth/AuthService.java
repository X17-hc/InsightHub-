package com.insighthub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.insighthub.auth.dto.LoginRequest;
import com.insighthub.auth.dto.RegisterRequest;
import com.insighthub.auth.dto.TokenResponse;
import com.insighthub.auth.dto.UserProfileResponse;
import com.insighthub.common.BusinessException;
import com.insighthub.config.JwtProperties;
import com.insighthub.security.JwtService;
import com.insighthub.security.SecurityUtils;

/**
 * 注册 / 登录 / 刷新令牌。
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw BusinessException.conflict("USERNAME_EXISTS", "username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw BusinessException.conflict("EMAIL_EXISTS", "email already exists");
        }
        String userId = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String hash = passwordEncoder.encode(request.getPassword());
        String display = request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? request.getUsername()
                : request.getDisplayName();
        userRepository.insertUser(userId, request.getUsername(), hash, request.getEmail(), display);
        return issueTokens(userId, request.getUsername());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserRepository.UserRow user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> BusinessException.unauthorized("invalid username or password"));
        if (user.status() != 1) {
            throw BusinessException.forbidden("user disabled");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.passwordHash())) {
            throw BusinessException.unauthorized("invalid username or password");
        }
        userRepository.touchLastLogin(user.id());
        return issueTokens(user.id(), user.username());
    }

    @Transactional
    public TokenResponse refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        UserRepository.RefreshTokenRow row = userRepository.findRefreshToken(hash)
                .orElseThrow(() -> BusinessException.unauthorized("invalid refresh token"));
        if (row.revoked() || row.expiresAt().toInstant().isBefore(Instant.now())) {
            throw BusinessException.unauthorized("refresh token expired or revoked");
        }
        userRepository.revokeRefreshToken(row.id());
        UserRepository.UserRow user = userRepository.findById(row.userId())
                .orElseThrow(() -> BusinessException.unauthorized("user not found"));
        return issueTokens(user.id(), user.username());
    }

    public UserProfileResponse me() {
        String userId = SecurityUtils.requireUserId();
        UserRepository.UserRow user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("user not found"));
        return new UserProfileResponse(user.id(), user.username(), user.email(), user.displayName());
    }

    private TokenResponse issueTokens(String userId, String username) {
        String access = jwtService.createAccessToken(userId, username);
        String refresh = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Instant exp = Instant.now().plus(jwtProperties.getRefreshExpireDays(), ChronoUnit.DAYS);
        userRepository.insertRefreshToken(
                "rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                userId,
                sha256(refresh),
                Timestamp.from(exp));
        return new TokenResponse(access, refresh, userId, username);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
