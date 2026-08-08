package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.entity.Report;
import com.mybatisflex.core.BaseMapper;

/**
 * 研究报告 Mapper（插入等通过 BaseMapper#insert / save 完成）。
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    @Select("""
            SELECT id AS id,
                   task_id AS taskId,
                   workspace_id AS workspaceId,
                   version AS version,
                   title AS title,
                   markdown_content AS markdownContent,
                   status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM report
            WHERE task_id = #{taskId}
              AND workspace_id = #{workspaceId}
            ORDER BY version DESC, created_at DESC
            LIMIT 1
            """)
    Report findLatestByTaskAndWorkspace(
            @Param("taskId") String taskId,
            @Param("workspaceId") String workspaceId);
}
