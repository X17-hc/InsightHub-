package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskPlanRevisionMapper;
import com.hechang.insighthub.model.dto.task.PlanRevisionResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.entity.TaskPlanRevision;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.service.PlanApplicationService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.core.query.QueryChain;
import com.mybatisflex.spring.service.impl.ServiceImpl;

@Service
public class PlanApplicationServiceImpl extends ServiceImpl<TaskPlanRevisionMapper, TaskPlanRevision>
        implements PlanApplicationService {

    private static final List<String> CURRENT_PLAN_STATUSES = List.of("PENDING", "APPROVED");
    private static final String PLAN_PENDING = "PENDING";

    @Resource
    private ResearchTaskMapper taskMapper;
    @Resource
    private WorkspaceAccessService accessService;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public PlanRevisionResponse current(String workspaceId, String taskId) {
        requireTaskMember(workspaceId, taskId);
        return toResponse(findCurrentRevision(workspaceId, taskId));
    }

    @Override
    public List<PlanRevisionResponse> history(String workspaceId, String taskId) {
        requireTaskMember(workspaceId, taskId);
        return queryChain()
                .eq(TaskPlanRevision::getTaskId, taskId)
                .eq(TaskPlanRevision::getWorkspaceId, workspaceId)
                .orderBy(TaskPlanRevision::getRevisionNo, false)
                .list()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void recordPlannerResult(
            String taskId,
            String workspaceId,
            String creatorId,
            String runId,
            Map<String, Object> eventData) {
        ResearchTask task = taskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        if (!TaskStatus.PLANNING.matches(task.getStatus()) && !TaskStatus.RUNNING.matches(task.getStatus())) {
            return;
        }

        PlanPayload payload = parsePlanPayload(eventData);
        TaskPlanRevision revision = newPendingRevision(taskId, workspaceId, creatorId, payload);
        if (!save(revision)) {
            throw new IllegalStateException("persist plan revision failed");
        }

        int updated = taskMapper.updatePlanProjection(
                taskId,
                workspaceId,
                revision.getId(),
                revision.getPlanJson(),
                0,
                runId,
                TaskStatus.WAITING_APPROVAL.name());
        if (updated != 1) {
            throw BusinessException.conflict("TASK_STATE_CHANGED", "task changed while persisting plan");
        }
    }

    private TaskPlanRevision findCurrentRevision(String workspaceId, String taskId) {
        return queryChain()
                .eq(TaskPlanRevision::getTaskId, taskId)
                .eq(TaskPlanRevision::getWorkspaceId, workspaceId)
                .in(TaskPlanRevision::getStatus, CURRENT_PLAN_STATUSES)
                .orderBy(TaskPlanRevision::getRevisionNo, false)
                .limit(1)
                .one();
    }

    private void requireTaskMember(String workspaceId, String taskId) {
        accessService.requireCurrentMember(workspaceId);
        boolean exists = QueryChain.of(taskMapper)
                .eq(ResearchTask::getId, taskId)
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .exists();
        if (!exists) {
            throw BusinessException.notFound("task not found");
        }
    }

    private PlanPayload parsePlanPayload(Map<String, Object> eventData) {
        if (eventData == null) {
            throw BusinessException.badRequest("INVALID_PLAN_EVENT", "PLAN_CREATED has no event data");
        }
        Object rawPlan = eventData.get("plan");
        Object rawHash = eventData.get("planHash");
        String hash = rawHash instanceof String value ? value : "";
        if (!(rawPlan instanceof Map<?, ?> plan) || hash.isBlank()) {
            throw BusinessException.badRequest("INVALID_PLAN_EVENT", "PLAN_CREATED has no plan/planHash");
        }
        try {
            String json = objectMapper.writeValueAsString(plan);
            return new PlanPayload(json, hash, revisionNo(eventData.get("planRevision")));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("serialize planner plan failed", ex);
        }
    }

    private static int revisionNo(Object rawRevision) {
        if (rawRevision == null) {
            return 1;
        }
        if (rawRevision instanceof Number number) {
            int revisionNo = number.intValue();
            if (revisionNo < 1) {
                throw BusinessException.badRequest("INVALID_PLAN_EVENT", "planRevision must be greater than 0");
            }
            return revisionNo;
        }
        throw BusinessException.badRequest("INVALID_PLAN_EVENT", "planRevision must be a number");
    }

    private static TaskPlanRevision newPendingRevision(
            String taskId,
            String workspaceId,
            String creatorId,
            PlanPayload payload) {
        TaskPlanRevision row = new TaskPlanRevision();
        row.setId("plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        row.setTaskId(taskId);
        row.setWorkspaceId(workspaceId);
        row.setRevisionNo(payload.revisionNo());
        row.setStatus(PLAN_PENDING);
        row.setPlanJson(payload.planJson());
        row.setPlanHash(payload.planHash());
        row.setCreatedBy(creatorId);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private PlanRevisionResponse toResponse(TaskPlanRevision row) {
        if (row == null) {
            throw BusinessException.notFound("plan not found");
        }
        try {
            JsonNode plan = objectMapper.readTree(row.getPlanJson());
            return new PlanRevisionResponse(
                    row.getId(),
                    row.getTaskId(),
                    row.getWorkspaceId(),
                    row.getRevisionNo(),
                    row.getStatus(),
                    plan,
                    row.getPlanHash(),
                    row.getRevisionInstruction(),
                    row.getCreatedBy(),
                    row.getApprovedBy(),
                    row.getCreatedAt(),
                    row.getApprovedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid persisted plan JSON", ex);
        }
    }

    private record PlanPayload(String planJson, String planHash, int revisionNo) {
    }
}
