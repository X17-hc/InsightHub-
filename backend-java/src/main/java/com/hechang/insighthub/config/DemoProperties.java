package com.hechang.insighthub.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示用户 / 工作空间（隔离验收用双空间）。
 */
@ConfigurationProperties(prefix = "insighthub.demo")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DemoProperties {

    /** 是否在启动时写入演示账号与默认 Agent（生产应关闭）。 */
    private boolean seedEnabled = false;

    /** 仅在显式启用 seed 时使用；不得在源码中提供固定口令。 */
    private String password = "";

    private String userId = "user-demo";
    private String workspaceId = "workspace-demo";
    private String userBId = "user-demo-b";
    private String workspaceBId = "workspace-demo-b";

}
