package com.hechang.insighthub.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;
import com.hechang.insighthub.model.dto.auth.LoginRequest;
import com.hechang.insighthub.model.dto.auth.RefreshRequest;
import com.hechang.insighthub.model.dto.auth.RegisterRequest;
import com.hechang.insighthub.model.dto.auth.TokenResponse;
import com.hechang.insighthub.model.dto.auth.UserProfileResponse;
import com.hechang.insighthub.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 认证 API。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册")
    public BaseResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResultUtils.success(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录")
    public BaseResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResultUtils.success(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新 Access Token")
    public BaseResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResultUtils.success(authService.refresh(request.getRefreshToken()));
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户")
    public BaseResponse<UserProfileResponse> me() {
        return ResultUtils.success(authService.me());
    }
}
