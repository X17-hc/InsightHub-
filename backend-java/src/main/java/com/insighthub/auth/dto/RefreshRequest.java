package com.insighthub.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 */
public class RefreshRequest {

    @NotBlank
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
