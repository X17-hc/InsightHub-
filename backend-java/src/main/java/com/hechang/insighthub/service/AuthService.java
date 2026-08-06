package com.hechang.insighthub.service;

import com.hechang.insighthub.model.dto.auth.LoginRequest;
import com.hechang.insighthub.model.dto.auth.RegisterRequest;
import com.hechang.insighthub.model.dto.auth.TokenResponse;
import com.hechang.insighthub.model.dto.auth.UserProfileResponse;
import com.hechang.insighthub.model.entity.SysUser;
import com.mybatisflex.core.service.IService;

/**
 * 注册 / 登录 / 刷新令牌。
 */
public interface AuthService extends IService<SysUser> {

    /** 注册并签发令牌 */
    TokenResponse register(RegisterRequest request);

    /** 登录并签发令牌 */
    TokenResponse login(LoginRequest request);

    /** 使用 refresh token 换发新令牌 */
    TokenResponse refresh(String refreshToken);

    /** 当前登录用户资料 */
    UserProfileResponse me();
}
