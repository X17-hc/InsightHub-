package com.hechang.insighthub.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hechang.insighthub.config.JwtProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.SysRefreshTokenMapper;
import com.hechang.insighthub.mapper.SysUserMapper;
import com.hechang.insighthub.model.dto.auth.LoginRequest;
import com.hechang.insighthub.model.dto.auth.RegisterRequest;
import com.hechang.insighthub.model.dto.auth.TokenResponse;
import com.hechang.insighthub.model.dto.auth.UserProfileResponse;
import com.hechang.insighthub.model.entity.SysRefreshToken;
import com.hechang.insighthub.model.entity.SysUser;
import com.hechang.insighthub.security.JwtService;
import com.hechang.insighthub.security.SecurityUtils;
import com.hechang.insighthub.service.AuthService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mybatisflex.core.update.UpdateChain;

/**
 * 注册 / 登录 / 刷新令牌实现。
 */
@Service
public class AuthServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements AuthService {

    private final SysRefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthServiceImpl(
            SysRefreshTokenMapper refreshTokenMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Override
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (count(QueryWrapper.create().eq(SysUser::getUsername, request.getUsername())) > 0) {
            throw BusinessException.conflict("USERNAME_EXISTS", "username already exists");
        }
        if (count(QueryWrapper.create().eq(SysUser::getEmail, request.getEmail())) > 0) {
            throw BusinessException.conflict("EMAIL_EXISTS", "email already exists");
        }
        String userId = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String hash = passwordEncoder.encode(request.getPassword());
        String display = request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? request.getUsername()
                : request.getDisplayName();

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(request.getUsername());
        user.setPasswordHash(hash);
        user.setEmail(request.getEmail());
        user.setDisplayName(display);
        user.setStatus(1);
        save(user);

        return issueTokens(userId, request.getUsername());
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        SysUser user = getOne(QueryWrapper.create().eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            throw BusinessException.unauthorized("invalid username or password");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.forbidden("user disabled");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw BusinessException.unauthorized("invalid username or password");
        }
        UpdateChain.of(mapper)
                .setRaw(SysUser::getLastLoginAt, "NOW()")
                .eq(SysUser::getId, user.getId())
                .update();
        return issueTokens(user.getId(), user.getUsername());
    }

    @Override
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        SysRefreshToken row = refreshTokenMapper.selectOneByQuery(
                QueryWrapper.create().eq(SysRefreshToken::getTokenHash, hash));
        if (row == null) {
            throw BusinessException.unauthorized("invalid refresh token");
        }
        boolean revoked = row.getRevoked() != null && row.getRevoked() == 1;
        boolean expired = row.getExpiresAt() == null
                || row.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC));
        if (revoked || expired) {
            throw BusinessException.unauthorized("refresh token expired or revoked");
        }
        UpdateChain.of(refreshTokenMapper)
                .set(SysRefreshToken::getRevoked, 1)
                .eq(SysRefreshToken::getId, row.getId())
                .update();
        SysUser user = getById(row.getUserId());
        if (user == null) {
            throw BusinessException.unauthorized("user not found");
        }
        return issueTokens(user.getId(), user.getUsername());
    }

    @Override
    public UserProfileResponse me() {
        String userId = SecurityUtils.requireUserId();
        SysUser user = getById(userId);
        if (user == null) {
            throw BusinessException.notFound("user not found");
        }
        return new UserProfileResponse(
                user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName());
    }

    /** 签发 access + refresh，并将 refresh 哈希入库 */
    private TokenResponse issueTokens(String userId, String username) {
        String access = jwtService.createAccessToken(userId, username);
        String refresh = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant exp = Instant.now().plus(jwtProperties.getRefreshExpireDays(), ChronoUnit.DAYS);

        SysRefreshToken token = new SysRefreshToken();
        token.setId("rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        token.setUserId(userId);
        token.setTokenHash(sha256(refresh));
        token.setExpiresAt(LocalDateTime.ofInstant(exp, ZoneOffset.UTC));
        token.setRevoked(0);
        token.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        refreshTokenMapper.insert(token);

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
