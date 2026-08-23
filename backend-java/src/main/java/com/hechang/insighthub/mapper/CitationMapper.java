package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.Citation;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;

/** 报告引用 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface CitationMapper extends BaseMapper<Citation> {
    default List<Citation> listByReportId(String reportId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(Citation::getReportId, reportId)
                .orderBy(Citation::getCitationNo, true));
    }

    default int deleteByTaskId(String taskId) {
        return deleteByQuery(QueryWrapper.create().eq(Citation::getTaskId, taskId));
    }
}
