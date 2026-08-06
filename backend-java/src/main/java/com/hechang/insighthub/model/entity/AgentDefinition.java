package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * Agent 定义实体，对应表 agent_definition。
 */
@Data
@Table("agent_definition")
public class AgentDefinition {

    /** Agent 主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("workspace_id")
    private String workspaceId;

    private String name;

    @Column("agent_type")
    private String agentType;

    /** 运行时：JAVA / PYTHON */
    private String runtime;

    @Column("model_config_id")
    private String modelConfigId;

    @Column("prompt_version")
    private String promptVersion;

    @Column("system_prompt")
    private String systemPrompt;

    /** 允许使用的工具 ID/权限列表 JSON */
    @Column("tool_permissions")
    private String toolPermissions;

    /** 是否启用：1=是 0=否 */
    private Integer enabled;

    /** 配置版本号 */
    private Integer version;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
