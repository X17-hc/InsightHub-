package com.hechang.insighthub.service.impl;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.AgentDefinitionMapper;
import com.hechang.insighthub.model.dto.agent.AgentResponse;
import com.hechang.insighthub.model.dto.agent.CreateAgentRequest;
import com.hechang.insighthub.model.dto.agent.UpdateAgentRequest;
import com.hechang.insighthub.model.entity.AgentDefinition;
import com.hechang.insighthub.security.SecurityUtils;
import com.hechang.insighthub.service.AgentService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.spring.service.impl.ServiceImpl;

/**
 * Agent 配置业务实现。
 */
@Service
public class AgentServiceImpl extends ServiceImpl<AgentDefinitionMapper, AgentDefinition> implements AgentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "PLANNER", "SUPERVISOR", "WEB_RESEARCHER", "KNOWLEDGE", "DATA_ANALYST", "CRITIC", "WRITER");

    private static final Set<String> ALLOWED_RUNTIMES = Set.of("PYTHON", "JAVA");

    private final WorkspaceAccessService accessService;

    public AgentServiceImpl(WorkspaceAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public List<AgentResponse> list(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return mapper.listByWorkspace(workspaceId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public AgentResponse create(String workspaceId, CreateAgentRequest request) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, userId);
        String type = request.getAgentType().trim().toUpperCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw BusinessException.badRequest("INVALID_AGENT_TYPE", "unsupported agentType");
        }
        String id = "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String runtime = request.getRuntime() == null ? "PYTHON" : request.getRuntime().trim().toUpperCase();
        if (!ALLOWED_RUNTIMES.contains(runtime)) {
            throw BusinessException.badRequest("INVALID_RUNTIME", "runtime must be PYTHON or JAVA");
        }

        AgentDefinition entity = new AgentDefinition();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setName(request.getName());
        entity.setAgentType(type);
        entity.setRuntime(runtime);
        entity.setPromptVersion(request.getPromptVersion());
        entity.setSystemPrompt(request.getSystemPrompt());
        entity.setEnabled(request.isEnabled() ? 1 : 0);
        entity.setVersion(1);
        save(entity);

        return toResponse(mapper.findByIdAndWorkspace(id, workspaceId));
    }

    @Override
    @Transactional
    public AgentResponse update(String workspaceId, String agentId, UpdateAgentRequest request) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, userId);
        if (mapper.findByIdAndWorkspace(agentId, workspaceId) == null) {
            throw BusinessException.notFound("agent not found");
        }
        mapper.updateBasic(
                agentId,
                workspaceId,
                request.getName(),
                request.getSystemPrompt(),
                request.getPromptVersion());
        return toResponse(mapper.findByIdAndWorkspace(agentId, workspaceId));
    }

    @Override
    @Transactional
    public AgentResponse enable(String workspaceId, String agentId, boolean enabled) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, userId);
        if (mapper.findByIdAndWorkspace(agentId, workspaceId) == null) {
            throw BusinessException.notFound("agent not found");
        }
        mapper.updateEnabled(agentId, workspaceId, enabled ? 1 : 0);
        return toResponse(mapper.findByIdAndWorkspace(agentId, workspaceId));
    }

    private AgentResponse toResponse(AgentDefinition row) {
        return new AgentResponse(
                row.getId(),
                row.getWorkspaceId(),
                row.getName(),
                row.getAgentType(),
                row.getRuntime(),
                row.getPromptVersion(),
                row.getSystemPrompt(),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getVersion() == null ? 0 : row.getVersion());
    }
}
