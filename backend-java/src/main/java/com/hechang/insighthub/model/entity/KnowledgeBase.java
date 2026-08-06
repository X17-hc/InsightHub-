package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 知识库元数据实体，对应表 knowledge_base。
 */
@Data
@Table("knowledge_base")
public class KnowledgeBase {

    /** 知识库主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("workspace_id")
    private String workspaceId;

    private String name;

    private String description;

    /** Embedding 模型标识 */
    @Column("embedding_model")
    private String embeddingModel;

    /** 分块策略：PARENT_CHILD / SEMANTIC / FIXED */
    @Column("chunk_strategy")
    private String chunkStrategy;

    /** 状态：ACTIVE / INDEXING / DISABLED */
    private String status;

    /** 文档数量缓存 */
    @Column("doc_count")
    private Integer docCount;

    @Column("created_by")
    private String createdBy;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
