package com.hechang.insighthub.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.hechang.insighthub.model.dto.knowledge.CreateKnowledgeBaseRequest;
import com.hechang.insighthub.model.dto.knowledge.DocumentResponse;
import com.hechang.insighthub.model.dto.knowledge.KnowledgeBaseResponse;
import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.mybatisflex.core.service.IService;

/**
 * 知识库与文档业务。
 */
public interface KnowledgeService extends IService<KnowledgeBase> {

    /** 创建知识库 */
    KnowledgeBaseResponse create(String workspaceId, CreateKnowledgeBaseRequest request);

    /** 列出工作空间内知识库 */
    List<KnowledgeBaseResponse> list(String workspaceId);

    /** 获取知识库详情 */
    KnowledgeBaseResponse get(String workspaceId, String kbId);

    /** 禁用知识库（软删除）并清理向量 */
    KnowledgeBaseResponse disable(String workspaceId, String kbId);

    /** 上传文档并异步入库 */
    DocumentResponse uploadDocument(String workspaceId, String kbId, MultipartFile file);

    /** 列出知识库文档 */
    List<DocumentResponse> listDocuments(String workspaceId, String kbId);

    /** 获取文档详情 */
    DocumentResponse getDocument(String workspaceId, String kbId, String docId);

    /** 重新索引文档 */
    DocumentResponse reindex(String workspaceId, String kbId, String docId);

    /**
     * 异步解析入库（PENDING→PARSING→INDEXED/FAILED）。
     * 若当前已是 PARSING 则跳过。
     */
    void ingestDocument(String workspaceId, String kbId, String documentId);
}
