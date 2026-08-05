package com.insighthub.auth.dto;

/**
 * 当前用户资料。
 */
public class UserProfileResponse {

    private String userId;
    private String username;
    private String email;
    private String displayName;

    public UserProfileResponse() {
    }

    public UserProfileResponse(String userId, String username, String email, String displayName) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
