package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.AgentDefinition;
import com.mybatisflex.core.BaseMapper;

/** Agent 定义 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinition> {
}
