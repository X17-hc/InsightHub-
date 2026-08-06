package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.entity.Citation;
import com.mybatisflex.core.BaseMapper;

/**
 * 报告引用 Mapper（insert 使用 BaseMapper）。
 */
@Mapper
public interface CitationMapper extends BaseMapper<Citation> {

    /** 按任务删除全部引用（重跑/清理） */
    @Delete("DELETE FROM citation WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);

    /** 按任务列出引用 */
    @Select("""
            SELECT id AS id,
                   report_id AS reportId,
                   task_id AS taskId,
                   citation_no AS citationNo,
                   source_title AS sourceTitle,
                   source_uri AS sourceUri,
                   source_type AS sourceType,
                   document_id AS documentId,
                   chunk_id AS chunkId,
                   quoted_text AS quotedText,
                   verified AS verified,
                   created_at AS createdAt
            FROM citation
            WHERE task_id = #{taskId}
            ORDER BY citation_no ASC
            """)
    List<Citation> listByTaskId(@Param("taskId") String taskId);
}
