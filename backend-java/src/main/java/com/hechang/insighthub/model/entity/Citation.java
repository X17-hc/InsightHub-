package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 报告引用实体，对应表 citation。
 */
@Data
@Table("citation")
public class Citation {

    /** 引用主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("report_id")
    private String reportId;

    @Column("task_id")
    private String taskId;

    /** 报告内引用编号，如 [1] */
    @Column("citation_no")
    private Integer citationNo;

    @Column("source_title")
    private String sourceTitle;

    @Column("source_uri")
    private String sourceUri;

    /** 来源类型：WEB / KNOWLEDGE / ANALYSIS */
    @Column("source_type")
    private String sourceType;

    @Column("document_id")
    private String documentId;

    @Column("chunk_id")
    private String chunkId;

    @Column("quoted_text")
    private String quotedText;

    /** 是否通过校验：1=是 0=否 */
    private Integer verified;

    @Column("verification_status")
    private String verificationStatus;

    @Column("verification_reason")
    private String verificationReason;

    @Column("canonical_uri")
    private String canonicalUri;

    @Column("final_uri")
    private String finalUri;

    @Column("retrieved_at")
    private LocalDateTime retrievedAt;

    @Column("content_hash")
    private String contentHash;

    @Column("http_status")
    private Integer httpStatus;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;
}
