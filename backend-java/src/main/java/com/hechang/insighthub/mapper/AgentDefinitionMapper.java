package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.AgentDefinition;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.List;

/** Agent 定义 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinition> {
    default List<AgentDefinition> listByWorkspace(String workspaceId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(AgentDefinition::getWorkspaceId, workspaceId)
                .orderBy(AgentDefinition::getAgentType, true)
                .orderBy(AgentDefinition::getName, true));
    }

    default AgentDefinition findByIdAndWorkspace(String id, String workspaceId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(AgentDefinition::getId, id)
                .eq(AgentDefinition::getWorkspaceId, workspaceId));
    }
}
