package com.hechang.insighthub.service.impl;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.integration.AgentStreamClient;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.TaskExecutionService;
import lombok.RequiredArgsConstructor;

/**
 * 异步消费 Python NDJSON 流并落库、更新投影及推送 SSE。
 *
 * <p>本类运行在有界 agentStreamExecutor，不开启数据库长事务。每个 NDJSON 行
 * 单独完成幂等落库/投影，远程流式等待期间不持有数据库连接。TaskStreamLease
 * 只阻止本 Java 进程中的旧流覆盖新流；跨进程互斥由 Agent execution lease 和
 * runId/CAS 条件共同保证。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);

    private final AgentStreamClient agentStreamClient;
    private final ResearchTaskMapper researchTaskMapper;
    private final TaskEventService taskEventService;
    private final TaskResultFinalizer resultFinalizer;
    private final TaskEventSideEffectHandler sideEffectHandler;
    private final TaskControlRedis taskControlRedis;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskEventSseHub sseHub;
    private final TaskStreamLease streamLease;
    private final ObjectMapper objectMapper;
    private final TaskProperties taskProperties;

    @Override
    @Async("agentStreamExecutor")
    public void executeStream(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            boolean resume,
            String runId,
            int planRevision) {
        executeStreamInternal(null, taskId, workspaceId, userId, query, traceId, resume, runId, planRevision);
    }

    @Override
    public void executeDispatch(com.hechang.insighthub.service.TaskDispatchCommand command) {
        executeStreamInternal(command, command.taskId(), command.workspaceId(), command.userId(),
                command.query(), command.traceId(), "EXECUTE".equals(command.phase()),
                command.runId(), command.planRevision());
    }

    private void executeStreamInternal(
            com.hechang.insighthub.service.TaskDispatchCommand command,
            String taskId, String workspaceId, String userId, String query, String traceId, boolean resume,
            String requestedRunId, int planRevision) {
        // generation 使 pause/retry 后迟到的旧回调失效；它不是持久化租约。
        String generation = streamLease.acquire(taskId);
        AtomicInteger badLines = new AtomicInteger();
        try {
            int timeout = taskProperties.getDefaultTimeoutSeconds();
            if (resume) {
                if (command != null && command.approvedPlanHash() != null) {
                    agentStreamClient.approvePlan(taskId, command.runId(), command.approvedPlanHash(), traceId, timeout,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                } else {
                    agentStreamClient.resumeTask(taskId, null, traceId, timeout,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                }
            } else {
                long nextEventId = taskEventService.maxEventNo(taskId) + 1;
                ResearchTask currentTask = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
                List<String> kbIds = parseKbIds(currentTask);
                if (command != null) {
                    agentStreamClient.streamTask(command, timeout, nextEventId <= 1 ? null : nextEventId,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                } else {
                    String idem = taskId + "-stream-" + System.currentTimeMillis();
                    agentStreamClient.streamTask(taskId, workspaceId, userId, query, traceId, timeout,
                            nextEventId <= 1 ? null : nextEventId, idem, requestedRunId, planRevision, kbIds,
                            currentTask != null && Boolean.TRUE.equals(currentTask.getEnableDataAnalysis()),
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                }
            }
            if (!streamLease.isCurrent(taskId, generation)) {
                return;
            }
            // Agent 必须以 TASK_RESULT 收尾；连接正常结束但 DB 仍非终态属于协议不完整。
            ResearchTask row = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
            if (row != null && !isTerminal(row.getStatus())
                    && !TaskStatus.PAUSED.matches(row.getStatus())
                    && !TaskStatus.WAITING_APPROVAL.matches(row.getStatus())) {
                resultFinalizer.markFailed(
                        taskId, workspaceId, null, "AGENT_STREAM_INCOMPLETE", "stream ended without result");
            }
        } catch (Exception ex) {
            if (!streamLease.isCurrent(taskId, generation)) {
                log.info("ignore stream error after lease invalidate taskId={}", taskId);
                return;
            }
            log.error("executeStream failed taskId={}", taskId, ex);
            resultFinalizer.markFailed(taskId, workspaceId, null, "AGENT_STREAM_FAILED", ex.getMessage());
            taskControlRedis.setControl(
                    taskId,
                    TaskControlRedis.CONTROL_CANCELLED,
                    taskProperties.getDefaultTimeoutSeconds() + 600);
        } finally {
            streamLease.release(taskId, generation);
            ResearchTask row = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
            if (row != null && (isTerminal(row.getStatus())
                    || TaskStatus.PAUSED.matches(row.getStatus())
                    || TaskStatus.WAITING_APPROVAL.matches(row.getStatus()))) {
                slotTracker.releaseOnce(
                        taskId,
                        workspaceId,
                        permitId -> concurrencyService.release(workspaceId, permitId));
                if (isTerminal(row.getStatus())) {
                    sseHub.completeTask(taskId);
                }
            }
        }
    }

    private void handleLine(
            String taskId,
            String workspaceId,
            JsonNode node,
            AtomicInteger badLines,
            String generation) {
        if (!streamLease.isCurrent(taskId, generation)) {
            return;
        }
        if (node == null || node.isNull()) {
            return;
        }
        String type = text(node, "type");
        // TASK_RESULT 是唯一规范终态，必须先在短事务中保存报告/引用和任务投影，
        // 再发布给 SSE；TASK_FAILED 等过程事件不能提前完成任务。
        if ("TASK_RESULT".equals(type)) {
            TaskEventService.StoredEvent published = resultFinalizer.finalizeResult(taskId, workspaceId, node);
            if (published != null) {
                if (TaskStatus.PAUSED.matches(text(node, "status"))
                        || TaskStatus.WAITING_APPROVAL.matches(text(node, "status"))) {
                    slotTracker.releaseOnce(
                            taskId,
                            workspaceId,
                            permitId -> concurrencyService.release(workspaceId, permitId));
                }
                publishTaskResult(taskId, new PublishedTaskResult(published.eventNo(), published.json()));
            }
            return;
        }
        AgentEventDto event = taskEventService.toDto(node);
        if (event.getType() == null) {
            if (badLines.incrementAndGet() > 20) {
                throw new IllegalStateException("too many bad event lines");
            }
            return;
        }
        long eventNo = event.getEventId() == null ? 0L : event.getEventId();
        if (eventNo <= 0) {
            return;
        }
        // (task_id,event_no) 唯一约束吸收 Agent 重连、checkpoint 恢复和 Redis 重复投递。
        taskEventService.insertIgnoreDuplicate(taskId, eventNo, event);
        try {
            String json = objectMapper.writeValueAsString(node);
            taskControlRedis.publishEvent(taskId, json);
            sseHub.broadcastLocal(taskId, eventNo, event.getType(), json);
        } catch (Exception ex) {
            log.warn("publish event failed taskId={} eventNo={}", taskId, eventNo, ex);
        }
        sideEffectHandler.apply(taskId, workspaceId, event);
    }

    private void publishTaskResult(String taskId, PublishedTaskResult result) {
        try {
            taskControlRedis.publishEvent(taskId, result.json());
            sseHub.broadcastLocal(taskId, result.eventNo(), "TASK_RESULT", result.json());
        } catch (Exception ex) {
            log.warn("publish TASK_RESULT failed taskId={}", taskId, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static boolean isTerminal(String status) {
        return TaskStatus.isTerminal(status);
    }

    /** 从任务行解析 knowledge_base_ids JSON */
    private List<String> parseKbIds(ResearchTask row) {
        if (row == null || row.getKnowledgeBaseIds() == null || row.getKnowledgeBaseIds().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(row.getKnowledgeBaseIds(), new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            log.warn("parse knowledgeBaseIds failed taskId={}", row.getId());
            return List.of();
        }
    }

    private record PublishedTaskResult(long eventNo, String json) {
    }
}
