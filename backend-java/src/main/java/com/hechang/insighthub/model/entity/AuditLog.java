package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

@Data
@Table("audit_log")
public class AuditLog {
    @Id(keyType = KeyType.Auto) private Long id;
    @Column("workspace_id") private String workspaceId;
    @Column("user_id") private String userId;
    private String action;
    @Column("resource_type") private String resourceType;
    @Column("resource_id") private String resourceId;
    @Column("detail_json") private String detailJson;
    private String ip;
    @Column("created_at") private LocalDateTime createdAt;
}
