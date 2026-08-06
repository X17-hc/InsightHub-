package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 工作空间实体，对应表 workspace。
 */
@Data
@Table("workspace")
public class Workspace {

    /** 工作空间主键 */
    @Id(keyType = KeyType.None)
    private String id;

    private String name;

    private String description;

    @Column("owner_id")
    private String ownerId;

    /** 最大并发研究任务数 */
    @Column("max_concurrent_tasks")
    private Integer maxConcurrentTasks;

    /** 每月 Token 配额 */
    @Column("monthly_token_quota")
    private Long monthlyTokenQuota;

    /** 状态：1=正常 0=归档/禁用 */
    private Integer status;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
