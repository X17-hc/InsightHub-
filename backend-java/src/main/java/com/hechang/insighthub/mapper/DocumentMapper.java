package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.KbDocument;
import com.mybatisflex.core.BaseMapper;

/**
 * 知识库文档 Mapper。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<KbDocument> {

    /** 按知识库列出文档 */
    @Select("""
            SELECT id AS id,
                   knowledge_base_id AS knowledgeBaseId,
                   workspace_id AS workspaceId,
                   file_name AS fileName,
                   content_type AS contentType,
                   file_size AS fileSize,
                   content_hash AS contentHash,
                   source_uri AS sourceUri,
                   parse_status AS parseStatus,
                   chunk_count AS chunkCount,
                   error_message AS errorMessage,
                   uploaded_by AS uploadedBy,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND workspace_id = #{workspaceId}
            ORDER BY created_at DESC
            """)
    List<KbDocument> listByKb(
            @Param("knowledgeBaseId") String knowledgeBaseId,
            @Param("workspaceId") String workspaceId);

    /** 按 ID + 工作空间查询（强制租户隔离） */
    @Select("""
            SELECT id AS id,
                   knowledge_base_id AS knowledgeBaseId,
                   workspace_id AS workspaceId,
                   file_name AS fileName,
                   content_type AS contentType,
                   file_size AS fileSize,
                   content_hash AS contentHash,
                   source_uri AS sourceUri,
                   parse_status AS parseStatus,
                   chunk_count AS chunkCount,
                   error_message AS errorMessage,
                   uploaded_by AS uploadedBy,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    KbDocument findByIdAndWorkspace(@Param("id") String id, @Param("workspaceId") String workspaceId);

    /** 按内容哈希在同一知识库内查找（去重） */
    @Select("""
            SELECT id AS id,
                   knowledge_base_id AS knowledgeBaseId,
                   workspace_id AS workspaceId,
                   file_name AS fileName,
                   content_type AS contentType,
                   file_size AS fileSize,
                   content_hash AS contentHash,
                   source_uri AS sourceUri,
                   parse_status AS parseStatus,
                   chunk_count AS chunkCount,
                   error_message AS errorMessage,
                   uploaded_by AS uploadedBy,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND content_hash = #{contentHash}
            LIMIT 1
            """)
    KbDocument findByHash(
            @Param("knowledgeBaseId") String knowledgeBaseId,
            @Param("contentHash") String contentHash);

    /** 更新解析状态 / 分块数 / 错误信息 */
    @Update("""
            UPDATE document
            SET parse_status = #{parseStatus},
                chunk_count = #{chunkCount},
                error_message = #{errorMessage},
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateParseStatus(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("parseStatus") String parseStatus,
            @Param("chunkCount") Integer chunkCount,
            @Param("errorMessage") String errorMessage);

    /** 统计知识库文档数 */
    @Select("""
            SELECT COUNT(*) FROM document
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND workspace_id = #{workspaceId}
            """)
    int countByKb(
            @Param("knowledgeBaseId") String knowledgeBaseId,
            @Param("workspaceId") String workspaceId);
}
