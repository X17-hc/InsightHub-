package com.hechang.insighthub.service;

import java.util.List;

import com.hechang.insighthub.model.dto.agent.AgentResponse;
import com.hechang.insighthub.model.dto.agent.CreateAgentRequest;
import com.hechang.insighthub.model.dto.agent.UpdateAgentRequest;
import com.hechang.insighthub.model.entity.AgentDefinition;
import com.mybatisflex.core.service.IService;

/**
 * Agent 配置业务。
 */
public interface AgentService extends IService<AgentDefinition> {

    /** 列出工作空间内 Agent */
    List<AgentResponse> list(String workspaceId);

    /** 创建 Agent（需 ADMIN/OWNER） */
    AgentResponse create(String workspaceId, CreateAgentRequest request);

    /** 更新 Agent 基础信息 */
    AgentResponse update(String workspaceId, String agentId, UpdateAgentRequest request);

    /** 启用 / 停用 Agent */
    AgentResponse enable(String workspaceId, String agentId, boolean enabled);
}
