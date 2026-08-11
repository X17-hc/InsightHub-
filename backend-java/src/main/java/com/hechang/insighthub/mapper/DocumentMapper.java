package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.KbDocument;
import com.mybatisflex.core.BaseMapper;

/** 知识库文档 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
@Mapper
public interface DocumentMapper extends BaseMapper<KbDocument> {
}
