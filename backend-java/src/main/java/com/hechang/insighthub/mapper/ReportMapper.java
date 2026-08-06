package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.Report;
import com.mybatisflex.core.BaseMapper;

/**
 * 研究报告 Mapper（插入等通过 BaseMapper#insert / save 完成）。
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
