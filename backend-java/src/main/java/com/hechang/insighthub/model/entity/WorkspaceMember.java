package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 工作空间成员实体，对应表 workspace_member。
 */
@Data
@Table("workspace_member")
public class WorkspaceMember {

    /** 成员关系主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("workspace_id")
    private String workspaceId;

    @Column("user_id")
    private String userId;

    /** 角色：OWNER/ADMIN/MEMBER */
    private String role;

    @Column(value = "joined_at", onInsertValue = "now()")
    private LocalDateTime joinedAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
