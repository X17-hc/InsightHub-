package com.insighthub.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户资料。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private String userId;
    private String username;
    private String email;
    private String displayName;

}
