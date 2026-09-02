package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.KbDocument;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;

/** 知识库文档 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface DocumentMapper extends BaseMapper<KbDocument> {
    default KbDocument findByIdAndWorkspace(String id, String workspaceId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getWorkspaceId, workspaceId));
    }

    default KbDocument findByKnowledgeBaseAndContentHash(
            String knowledgeBaseId, String workspaceId, String contentHash) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(KbDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KbDocument::getWorkspaceId, workspaceId)
                .eq(KbDocument::getContentHash, contentHash));
    }

    default long countByKnowledgeBaseAndWorkspace(String knowledgeBaseId, String workspaceId) {
        return selectCountByQuery(QueryWrapper.create()
                .eq(KbDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KbDocument::getWorkspaceId, workspaceId));
    }

    default List<KbDocument> listByKnowledgeBaseAndWorkspace(String knowledgeBaseId, String workspaceId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(KbDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KbDocument::getWorkspaceId, workspaceId)
                .orderBy(KbDocument::getCreatedAt, false));
    }
}
