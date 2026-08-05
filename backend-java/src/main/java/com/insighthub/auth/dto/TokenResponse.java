package com.insighthub.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/刷新返回的双令牌。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private String userId;
    private String username;


    public TokenResponse(String accessToken, String refreshToken, String userId, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.username = username;
    }

}
