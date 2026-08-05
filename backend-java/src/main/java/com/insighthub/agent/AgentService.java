package com.insighthub.agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.insighthub.agent.dto.AgentResponse;
import com.insighthub.agent.dto.CreateAgentRequest;
import com.insighthub.agent.dto.UpdateAgentRequest;
import com.insighthub.common.BusinessException;
import com.insighthub.security.SecurityUtils;
import com.insighthub.workspace.WorkspaceAccessService;

/**
 * Agent 配置业务。
 */
@Service
public class AgentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "PLANNER", "SUPERVISOR", "WEB_RESEARCHER", "KNOWLEDGE", "DATA_ANALYST", "CRITIC", "WRITER");

    private static final Set<String> ALLOWED_RUNTIMES = Set.of("PYTHON", "JAVA");

    private final AgentRepository agentRepository;
    private final WorkspaceAccessService accessService;

    public AgentService(AgentRepository agentRepository, WorkspaceAccessService accessService) {
        this.agentRepository = agentRepository;
        this.accessService = accessService;
    }

    public List<AgentResponse> list(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return agentRepository.listByWorkspace(workspaceId).stream().map(this::toResponse).toList();
    }

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
        agentRepository.insert(
                id,
                workspaceId,
                request.getName(),
                type,
                runtime,
                request.getPromptVersion(),
                request.getSystemPrompt(),
                request.isEnabled(),
                1);
        return toResponse(agentRepository.findByIdAndWorkspace(id, workspaceId).orElseThrow());
    }

    @Transactional
    public AgentResponse update(String workspaceId, String agentId, UpdateAgentRequest request) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, userId);
        agentRepository.findByIdAndWorkspace(agentId, workspaceId)
                .orElseThrow(() -> BusinessException.notFound("agent not found"));
        agentRepository.update(
                agentId,
                workspaceId,
                request.getName(),
                request.getSystemPrompt(),
                request.getPromptVersion());
        return toResponse(agentRepository.findByIdAndWorkspace(agentId, workspaceId).orElseThrow());
    }

    @Transactional
    public AgentResponse enable(String workspaceId, String agentId, boolean enabled) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, userId);
        agentRepository.findByIdAndWorkspace(agentId, workspaceId)
                .orElseThrow(() -> BusinessException.notFound("agent not found"));
        agentRepository.setEnabled(agentId, workspaceId, enabled);
        return toResponse(agentRepository.findByIdAndWorkspace(agentId, workspaceId).orElseThrow());
    }

    private AgentResponse toResponse(AgentRepository.AgentRow row) {
        return new AgentResponse(
                row.id(),
                row.workspaceId(),
                row.name(),
                row.agentType(),
                row.runtime(),
                row.promptVersion(),
                row.systemPrompt(),
                row.enabled(),
                row.version());
    }
}
