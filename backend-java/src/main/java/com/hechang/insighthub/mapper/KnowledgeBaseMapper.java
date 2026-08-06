package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.mybatisflex.core.BaseMapper;

/**
 * 知识库 Mapper。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /** 按工作空间列出知识库 */
    @Select("""
            SELECT id AS id,
                   workspace_id AS workspaceId,
                   name AS name,
                   description AS description,
                   embedding_model AS embeddingModel,
                   chunk_strategy AS chunkStrategy,
                   status AS status,
                   doc_count AS docCount,
                   created_by AS createdBy,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_base
            WHERE workspace_id = #{workspaceId}
            ORDER BY created_at DESC
            """)
    List<KnowledgeBase> listByWorkspace(@Param("workspaceId") String workspaceId);

    /** 按 ID + 工作空间查询（强制租户隔离） */
    @Select("""
            SELECT id AS id,
                   workspace_id AS workspaceId,
                   name AS name,
                   description AS description,
                   embedding_model AS embeddingModel,
                   chunk_strategy AS chunkStrategy,
                   status AS status,
                   doc_count AS docCount,
                   created_by AS createdBy,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_base
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    KnowledgeBase findByIdAndWorkspace(@Param("id") String id, @Param("workspaceId") String workspaceId);

    /** 更新知识库状态 */
    @Update("""
            UPDATE knowledge_base
            SET status = #{status}, updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateStatus(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("status") String status);

    /** 更新文档数量缓存 */
    @Update("""
            UPDATE knowledge_base
            SET doc_count = #{docCount}, updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateDocCount(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("docCount") int docCount);
}
