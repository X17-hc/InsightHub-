package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 文档元数据实体，对应表 document（类名 KbDocument 避免与 JDK/歧义冲突）。
 */
@Data
@Table("document")
public class KbDocument {

    /** 文档主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("knowledge_base_id")
    private String knowledgeBaseId;

    @Column("workspace_id")
    private String workspaceId;

    @Column("file_name")
    private String fileName;

    @Column("content_type")
    private String contentType;

    @Column("file_size")
    private Long fileSize;

    /** 内容 SHA-256，用于同 KB 去重 */
    @Column("content_hash")
    private String contentHash;

    /** 本地存储路径或来源 URI */
    @Column("source_uri")
    private String sourceUri;

    /** 解析状态：PENDING / PARSING / INDEXED / FAILED */
    @Column("parse_status")
    private String parseStatus;

    @Column("chunk_count")
    private Integer chunkCount;

    @Column("error_message")
    private String errorMessage;

    @Column("uploaded_by")
    private String uploadedBy;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
