package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.Report;
import com.mybatisflex.core.BaseMapper;

/** 研究报告 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
