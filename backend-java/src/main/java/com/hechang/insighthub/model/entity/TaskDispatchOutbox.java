package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

@Data
@Table("task_dispatch_outbox")
public class TaskDispatchOutbox {
    @Id(keyType = KeyType.None) private String id;
    @Column("task_id") private String taskId;
    @Column("workspace_id") private String workspaceId;
    @Column("run_id") private String runId;
    private String phase;
    @Column("payload_json") private String payloadJson;
    private String status;
    @Column("attempt_count") private Integer attemptCount;
    @Column("next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column("last_error") private String lastError;
    @Column("created_at") private LocalDateTime createdAt;
    @Column("updated_at") private LocalDateTime updatedAt;
}
