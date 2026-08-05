package com.insighthub.workspace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 添加成员请求。
 */
public class AddMemberRequest {

    @NotBlank
    private String userId;

    /** OWNER / ADMIN / MEMBER，默认 MEMBER */
    private String role = "MEMBER";

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
