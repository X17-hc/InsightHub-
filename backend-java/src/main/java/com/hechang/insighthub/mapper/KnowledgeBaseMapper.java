package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;

/** 知识库 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
    default List<KnowledgeBase> listByWorkspace(String workspaceId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(KnowledgeBase::getWorkspaceId, workspaceId)
                .orderBy(KnowledgeBase::getCreatedAt, false));
    }

    default KnowledgeBase findByIdAndWorkspace(String id, String workspaceId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getWorkspaceId, workspaceId));
    }
}
