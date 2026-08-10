package com.hechang.insighthub.service.impl;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.entity.TaskEvent;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;

/**
 * SSE 连接管理：MySQL 回放 + Redis 订阅 + DB 轮询降级 + 心跳。
 */
@Component
public class TaskEventSseHub {

    private static final Logger log = LoggerFactory.getLogger(TaskEventSseHub.class);

    private final ResearchTaskMapper researchTaskMapper;
    private final TaskEventMapper taskEventMapper;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final TaskEventService taskEventService;
    private final TaskProperties taskProperties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ih-sse-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** taskId -> emitters */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EmitterSession>> sessions = new ConcurrentHashMap<>();

    public TaskEventSseHub(
            ResearchTaskMapper researchTaskMapper,
            TaskEventMapper taskEventMapper,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            TaskEventService taskEventService,
            TaskProperties taskProperties) {
        this.researchTaskMapper = researchTaskMapper;
        this.taskEventMapper = taskEventMapper;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.taskEventService = taskEventService;
        this.taskProperties = taskProperties;
    }

    /**
     * 建立 SSE：先回放再订阅 live；并启动 DB 轮询作为 Redis Pub/Sub 降级。
     *
     * @param taskId      任务 ID
     * @param workspaceId 工作空间（校验归属）
     * @param fromEventNo 已收到的最大 eventNo（不含）
     */
    public SseEmitter subscribe(String taskId, String workspaceId, long fromEventNo) {
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }

        SseEmitter emitter = new SseEmitter(0L); // 无超时，靠心跳
        AtomicLong lastSent = new AtomicLong(fromEventNo);
        EmitterSession session = new EmitterSession(taskId, emitter, lastSent);

        sessions.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(session);

        Runnable cleanup = () -> removeSession(taskId, session);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 回放历史
        List<TaskEvent> history = taskEventMapper.listAfterEventNo(taskId, fromEventNo);
        for (TaskEvent row : history) {
            long eventNo = row.getEventNo() == null ? 0L : row.getEventNo();
            sendEvent(session, eventNo, row.getEventType(), toClientJson(taskId, row));
            lastSent.set(Math.max(lastSent.get(), eventNo));
        }

        if (isTerminal(task.getStatus())) {
            emitter.complete();
            removeSession(taskId, session);
            return emitter;
        }

        // Redis 订阅（失败不阻断；靠 DB 轮询兜底）
        try {
            MessageListener listener = (Message message, byte[] pattern) -> {
                try {
                    String json = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                    dispatchLiveJson(session, json);
                } catch (Exception ex) {
                    log.warn("SSE live dispatch error taskId={}", taskId, ex);
                }
            };
            ChannelTopic topic = new ChannelTopic(TaskControlRedis.eventsChannel(taskId));
            listenerContainer.addMessageListener(listener, topic);
            session.listener = listener;
            session.topic = topic;
        } catch (Exception ex) {
            log.error("SSE Redis subscribe failed taskId={}, rely on DB poll", taskId, ex);
        }

        // 心跳
        int hb = Math.max(5, taskProperties.getSseHeartbeatSeconds());
        session.heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (Exception ex) {
                        cleanup.run();
                    }
                },
                hb,
                hb,
                TimeUnit.SECONDS);

        // Redis 宕机/漏推时：每 1s 从 MySQL 拉取 lastSent 之后的事件
        session.dbPoll = scheduler.scheduleAtFixedRate(
                () -> pollDbEvents(session, workspaceId),
                1,
                1,
                TimeUnit.SECONDS);

        return emitter;
    }

    /** 广播给本机已连接的 SSE（可选，主路径靠 Redis）。 */
    public void broadcastLocal(String taskId, long eventNo, String type, String json) {
        CopyOnWriteArrayList<EmitterSession> list = sessions.get(taskId);
        if (list == null) {
            return;
        }
        for (EmitterSession s : list) {
            if (eventNo > 0 && eventNo <= s.lastSent.get()) {
                continue;
            }
            sendRaw(s, eventNo, type, json);
            if (eventNo > 0) {
                s.lastSent.set(eventNo);
            }
        }
    }

    public void completeTask(String taskId) {
        CopyOnWriteArrayList<EmitterSession> list = sessions.remove(taskId);
        if (list == null) {
            return;
        }
        for (EmitterSession s : list) {
            removeSession(taskId, s);
            completeQuietly(s.emitter);
        }
    }

    private void pollDbEvents(EmitterSession session, String workspaceId) {
        if (session.closed.get()) {
            return;
        }
        try {
            long from = session.lastSent.get();
            List<TaskEvent> rows = taskEventMapper.listAfterEventNo(session.taskId, from);
            for (TaskEvent row : rows) {
                long eventNo = row.getEventNo() == null ? 0L : row.getEventNo();
                if (eventNo <= session.lastSent.get()) {
                    continue;
                }
                sendEvent(session, eventNo, row.getEventType(), toClientJson(session.taskId, row));
                session.lastSent.set(eventNo);
            }
            ResearchTask task = researchTaskMapper.findByIdAndWorkspace(session.taskId, workspaceId);
            if (task != null && isTerminal(task.getStatus())) {
                completeQuietly(session.emitter);
                removeSession(session.taskId, session);
            }
        } catch (Exception ex) {
            log.warn("SSE DB poll failed taskId={}", session.taskId, ex);
        }
    }

    private void dispatchLiveJson(EmitterSession session, String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        Object en = map.get("eventId");
        if (en == null) {
            en = map.get("eventNo");
        }
        long eventNo = en == null ? 0L : Long.parseLong(String.valueOf(en));
        if (eventNo > 0 && eventNo <= session.lastSent.get()) {
            return;
        }
        String type = String.valueOf(map.getOrDefault("type", "EVENT"));
        sendRaw(session, eventNo, type, json);
        if (eventNo > 0) {
            session.lastSent.set(eventNo);
        }
        // 仅在明确终态 TASK_RESULT 时关闭；TASK_COMPLETED 仍可能进入 GENERATING
        if ("TASK_RESULT".equals(type)) {
            Object status = map.get("status");
            if (status != null && isTerminal(String.valueOf(status))) {
                completeQuietly(session.emitter);
            }
        } else if ("TASK_FAILED".equals(type)) {
            completeQuietly(session.emitter);
        }
    }

    private void removeSession(String taskId, EmitterSession session) {
        session.closed.set(true);
        CopyOnWriteArrayList<EmitterSession> list = sessions.get(taskId);
        if (list != null) {
            list.remove(session);
            if (list.isEmpty()) {
                sessions.remove(taskId, list);
            }
        }
        if (session.heartbeat != null) {
            session.heartbeat.cancel(false);
        }
        if (session.dbPoll != null) {
            session.dbPoll.cancel(false);
        }
        if (session.listener != null && session.topic != null) {
            try {
                listenerContainer.removeMessageListener(session.listener, session.topic);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void sendEvent(EmitterSession session, long eventNo, String type, String json) {
        sendRaw(session, eventNo, type, json);
    }

    private void sendRaw(EmitterSession session, long eventNo, String type, String json) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .id(String.valueOf(eventNo))
                    .name(type == null ? "message" : type)
                    .data(json);
            session.emitter.send(builder);
        } catch (IOException ex) {
            removeSession(session.taskId, session);
            completeQuietly(session.emitter);
        }
    }

    private String toClientJson(String taskId, TaskEvent row) {
        return taskEventService.toClientJson(taskId, row);
    }

    private static boolean isTerminal(String status) {
        return TaskStatus.isTerminal(status);
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static final class EmitterSession {
        final String taskId;
        final SseEmitter emitter;
        final AtomicLong lastSent;
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile MessageListener listener;
        volatile ChannelTopic topic;
        volatile ScheduledFuture<?> heartbeat;
        volatile ScheduledFuture<?> dbPoll;

        EmitterSession(String taskId, SseEmitter emitter, AtomicLong lastSent) {
            this.taskId = taskId;
            this.emitter = emitter;
            this.lastSent = lastSent;
        }
    }
}
