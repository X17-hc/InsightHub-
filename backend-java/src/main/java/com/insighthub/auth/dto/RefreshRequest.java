package com.insighthub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 */
@Data
public class RefreshRequest {

    @NotBlank
    private String refreshToken;

}
