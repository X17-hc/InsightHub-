package com.insighthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 第 1 周演示用固定用户 / 工作空间。
 */
@ConfigurationProperties(prefix = "insighthub.demo")
public class DemoProperties {

    private String userId = "user-demo";
    private String workspaceId = "workspace-demo";

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }
}
