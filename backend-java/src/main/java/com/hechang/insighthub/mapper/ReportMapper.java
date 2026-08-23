package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.Report;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;

/** 研究报告 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface ReportMapper extends BaseMapper<Report> {
    default Report findLatestByTask(String workspaceId, String taskId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(Report::getWorkspaceId, workspaceId)
                .eq(Report::getTaskId, taskId)
                .orderBy(Report::getVersion, false)
                .orderBy(Report::getCreatedAt, false)
                .limit(1));
    }

    default List<Report> listByTask(String workspaceId, String taskId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(Report::getWorkspaceId, workspaceId)
                .eq(Report::getTaskId, taskId)
                .orderBy(Report::getVersion, false));
    }

    default Report findByTaskAndVersion(String workspaceId, String taskId, int version) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(Report::getWorkspaceId, workspaceId)
                .eq(Report::getTaskId, taskId)
                .eq(Report::getVersion, version));
    }

    default int deleteByTaskId(String taskId) {
        return deleteByQuery(QueryWrapper.create().eq(Report::getTaskId, taskId));
    }
}
