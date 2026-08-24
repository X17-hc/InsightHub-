package com.hechang.insighthub.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import com.hechang.insighthub.service.AuditLogService;
import com.hechang.insighthub.service.TaskDispatchCommand;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.mapper.TaskDispatchOutboxMapper;
import com.hechang.insighthub.model.entity.TaskDispatchOutbox;
import com.hechang.insighthub.model.dto.task.ApprovePlanRequest;
import com.hechang.insighthub.model.dto.task.RevisePlanRequest;
import com.hechang.insighthub.model.dto.task.PlanActionResponse;
import com.hechang.insighthub.service.CurrentWorkspaceAccess;
import com.mybatisflex.core.query.QueryChain;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlanApplicationServiceImpl extends ServiceImpl<TaskPlanRevisionMapper, TaskPlanRevision>
        implements PlanApplicationService {

    private static final List<String> CURRENT_PLAN_STATUSES = List.of("PENDING", "APPROVED");
    private static final String PLAN_PENDING = "PENDING";

    private final ResearchTaskMapper taskMapper;
    private final TaskPlanRevisionMapper revisionMapper;
    private final WorkspaceAccessService accessService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final TaskDispatchOutboxMapper outboxMapper;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskProperties taskProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskEventService taskEventService;

    @Override
    public PlanRevisionResponse current(String workspaceId, String taskId) {
        requireTaskMember(workspaceId, taskId);
        ResearchTask task = taskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task != null && TaskStatus.PLANNING.matches(task.getStatus())) {
            throw BusinessException.conflict("PLAN_GENERATING", "a revised plan is being generated");
        }
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
        TaskPlanRevision latest = revisionMapper.findLatestByTask(taskId);
        int allocatedRevision = latest == null ? 1 : latest.getRevisionNo() + 1;
        TaskPlanRevision revision = newPendingRevision(
                taskId, workspaceId, creatorId, payload, allocatedRevision);
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
            Object instruction = eventData.get("revisionInstruction");
            String revisionInstruction = instruction instanceof String value ? value : null;
            return new PlanPayload(json, hash, revisionNo(eventData.get("planRevision")), revisionInstruction);
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
            PlanPayload payload,
            int allocatedRevision) {
        TaskPlanRevision row = new TaskPlanRevision();
        row.setId("plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        row.setTaskId(taskId);
        row.setWorkspaceId(workspaceId);
        row.setRevisionNo(allocatedRevision);
        row.setStatus(PLAN_PENDING);
        row.setPlanJson(payload.planJson());
        row.setPlanHash(payload.planHash());
        row.setRevisionInstruction(payload.revisionInstruction());
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
                    row.getApprovalRemark(),
                    row.getCreatedAt(),
                    row.getApprovedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid persisted plan JSON", ex);
        }
    }

    @Override
    @Transactional
    public PlanActionResponse approve(String workspaceId, String taskId, ApprovePlanRequest request, String ip) {
        CurrentWorkspaceAccess actor = accessService.requireCurrentMember(workspaceId);
        String permitId = concurrencyService.tryAcquire(workspaceId, taskProperties.getDefaultTimeoutSeconds() + 600);
        boolean held = false;
        try {
            ResearchTask task = requireCreatorAndWaiting(workspaceId, taskId, actor);
            TaskPlanRevision revision = requirePendingRevision(task, workspaceId, request.expectedRevision());
            String approvalRemark = normalizeRemark(request.remark());
            if (revisionMapper.approvePending(revision.getId(), actor.userId(), approvalRemark, LocalDateTime.now()) != 1) {
                throw BusinessException.conflict("PLAN_ALREADY_CHANGED", "plan is no longer pending");
            }
            if (taskMapper.updatePlanAction(taskId, workspaceId, TaskStatus.RUNNING.name(), 1,
                    "dispatch_tasks", 30, task.getCurrentRunId(), revision.getId()) != 1) {
                throw BusinessException.conflict("TASK_STATE_CHANGED", "task changed while approving plan");
            }
            slotTracker.markHeld(taskId, workspaceId, permitId, taskProperties.getDefaultTimeoutSeconds() + 600);
            held = true;
            auditLogService.record(workspaceId, actor.userId(), "PLAN_APPROVE", "RESEARCH_TASK", taskId,
                    Map.of("revision", request.expectedRevision(), "runId", task.getCurrentRunId(),
                            "planHash", revision.getPlanHash(), "remarkLength", request.remark() == null ? 0 : request.remark().length()), ip);
            eventPublisher.publishEvent(new TaskDispatchRequested(
                    enqueue(commandFor(task, revision, actor.userId(), "EXECUTE", null, revision.getPlanHash()))));
            TaskEventService.StoredEvent approvedEvent = taskEventService.insertServerEvent(
                    taskId, task.getCurrentRunId(), "wait_for_approval", "PLAN_APPROVED",
                    Map.of("planRevision", revision.getRevisionNo(), "approvedBy", actor.userId(),
                            "hasRemark", approvalRemark != null));
            eventPublisher.publishEvent(new TaskEventPublished(taskId, approvedEvent));
            return new PlanActionResponse(taskId, revision.getRevisionNo(), TaskStatus.RUNNING.name(), task.getCurrentRunId());
        } catch (RuntimeException ex) {
            if (held) {
                slotTracker.releaseOnce(taskId, workspaceId, permit -> concurrencyService.release(workspaceId, permit));
            } else {
                concurrencyService.release(workspaceId, permitId);
            }
            throw ex;
        }
    }

    private static String normalizeRemark(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    @Override
    @Transactional
    public PlanActionResponse revise(String workspaceId, String taskId, RevisePlanRequest request, String ip) {
        CurrentWorkspaceAccess actor = accessService.requireCurrentMember(workspaceId);
        String permitId = concurrencyService.tryAcquire(workspaceId, taskProperties.getDefaultTimeoutSeconds() + 600);
        boolean held = false;
        try {
            ResearchTask task = requireCreatorAndWaiting(workspaceId, taskId, actor);
            TaskPlanRevision revision = requirePendingRevision(task, workspaceId, request.expectedRevision());
            if (revisionMapper.supersedePending(revision.getId()) != 1) {
                throw BusinessException.conflict("PLAN_ALREADY_CHANGED", "plan is no longer pending");
            }
            String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            int nextRevision = request.expectedRevision() + 1;
            if (taskMapper.resetPlanForRevision(taskId, workspaceId, TaskStatus.PLANNING.name(),
                    "create_plan", 10, runId) != 1) {
                throw BusinessException.conflict("TASK_STATE_CHANGED", "task changed while revising plan");
            }
            slotTracker.markHeld(taskId, workspaceId, permitId, taskProperties.getDefaultTimeoutSeconds() + 600);
            held = true;
            auditLogService.record(workspaceId, actor.userId(), "PLAN_REVISE", "RESEARCH_TASK", taskId,
                    Map.of("revision", request.expectedRevision(), "nextRevision", nextRevision, "runId", runId), ip);
            TaskEventService.StoredEvent revised = taskEventService.insertServerEvent(
                    taskId, runId, "create_plan", "PLAN_REVISED",
                    Map.of("previousRevision", request.expectedRevision(), "nextRevision", nextRevision));
            eventPublisher.publishEvent(new TaskEventPublished(taskId, revised));
            eventPublisher.publishEvent(new TaskDispatchRequested(enqueue(new TaskDispatchCommand(
                    taskId, workspaceId, actor.userId(), task.getQuery(), task.getTraceId(),
                    runId, "PLAN", nextRevision, request.revision(), null, knowledgeBaseIds(task),
                    Boolean.TRUE.equals(task.getEnableDataAnalysis())
                    ))));
            return new PlanActionResponse(taskId, nextRevision, TaskStatus.PLANNING.name(), runId);
        } catch (RuntimeException ex) {
            if (held) {
                slotTracker.releaseOnce(taskId, workspaceId, permit -> concurrencyService.release(workspaceId, permit));
            } else {
                concurrencyService.release(workspaceId, permitId);
            }
            throw ex;
        }
    }

    private ResearchTask requireCreatorAndWaiting(String workspaceId, String taskId, CurrentWorkspaceAccess actor) {
        ResearchTask task = taskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (!actor.userId().equals(task.getCreatorId())) throw BusinessException.forbidden("only task creator may approve or revise a plan");
        if (!TaskStatus.WAITING_APPROVAL.matches(task.getStatus())) {
            throw BusinessException.conflict("PLAN_NOT_WAITING", "task is not waiting for plan approval");
        }
        return task;
    }

    private TaskPlanRevision requirePendingRevision(ResearchTask task, String workspaceId, int expectedRevision) {
        TaskPlanRevision revision = findCurrentRevision(workspaceId, task.getId());
        if (revision == null || !"PENDING".equals(revision.getStatus()) || revision.getRevisionNo() != expectedRevision
                || !revision.getId().equals(task.getCurrentPlanRevisionId())) {
            throw BusinessException.conflict("PLAN_VERSION_STALE", "expected plan revision is no longer current");
        }
        return revision;
    }

    private TaskDispatchCommand commandFor(ResearchTask task, TaskPlanRevision revision, String userId,
            String phase, String instruction, String approvedHash) {
        return new TaskDispatchCommand(task.getId(), task.getWorkspaceId(), userId, task.getQuery(), task.getTraceId(),
                task.getCurrentRunId(), phase, revision.getRevisionNo(), instruction, approvedHash, knowledgeBaseIds(task),
                Boolean.TRUE.equals(task.getEnableDataAnalysis()));
    }

    private List<String> knowledgeBaseIds(ResearchTask task) {
        if (task.getKnowledgeBaseIds() == null || task.getKnowledgeBaseIds().isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = objectMapper.readValue(task.getKnowledgeBaseIds(), new TypeReference<List<String>>() {});
            return ids == null ? List.of() : List.copyOf(ids);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid persisted knowledge base ids", ex);
        }
    }

    private String enqueue(TaskDispatchCommand command) {
        try {
            TaskDispatchOutbox row = new TaskDispatchOutbox();
            row.setId("dispatch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            row.setTaskId(command.taskId()); row.setWorkspaceId(command.workspaceId()); row.setRunId(command.runId());
            row.setPhase(command.phase()); row.setPayloadJson(objectMapper.writeValueAsString(command));
            row.setStatus("PENDING"); row.setAttemptCount(0); row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
            outboxMapper.insert(row);
            return row.getId();
        } catch (Exception ex) {
            throw new IllegalStateException("create task dispatch outbox failed", ex);
        }
    }

    private record PlanPayload(String planJson, String planHash, int revisionNo, String revisionInstruction) {
    }
}
