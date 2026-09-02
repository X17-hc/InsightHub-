package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 锁定知识库聚合根，确保禁用知识库与新增文档不会并发穿透状态校验。
     * 返回 null 表示资源不存在或不属于该工作空间。
     */
    @Select("SELECT * FROM knowledge_base WHERE id = #{id} AND workspace_id = #{workspaceId} FOR UPDATE")
    KnowledgeBase findByIdAndWorkspaceForUpdate(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId);
}
