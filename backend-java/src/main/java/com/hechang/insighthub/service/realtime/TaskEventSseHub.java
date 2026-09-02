package com.hechang.insighthub.service.realtime;

import com.hechang.insighthub.service.task.TaskEventService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import lombok.RequiredArgsConstructor;

/**
 * SSE 连接管理：MySQL 回放 + Redis 订阅 + DB 轮询降级 + 心跳。
 *
 * <p>MySQL 事件表是可恢复事实来源，Redis Pub/Sub 只降低实时延迟。每个 session
 * 用 lastSent 去重，把历史回放与实时订阅衔接为 at-least-once 可恢复流；该类不
 * 承诺 exactly-once。所有定时任务、Redis listener 和 emitter 必须随 session
 * 关闭而释放，避免终态任务长期占用线程。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskEventSseHub {

    private static final Logger log = LoggerFactory.getLogger(TaskEventSseHub.class);

    private final ResearchTaskMapper researchTaskMapper;
    private final TaskEventMapper taskEventMapper;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final TaskEventService taskEventService;
    private final TaskProperties taskProperties;
    private final ScheduledExecutorService sseScheduler;
    private final AtomicLong activeSessionCount = new AtomicLong();

    /** taskId -> emitters */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EmitterSession>> sessions = new ConcurrentHashMap<>();

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

        long active = activeSessionCount.incrementAndGet();
        if (active > taskProperties.getSseMaxConnectionsTotal()) {
            activeSessionCount.decrementAndGet();
            throw BusinessException.tooManyRequests("SSE_CONNECTION_LIMIT", "too many active event streams");
        }

        SseEmitter emitter = new SseEmitter(taskProperties.getSseConnectionTimeoutSeconds() * 1000L);
        AtomicLong lastSent = new AtomicLong(fromEventNo);
        EmitterSession session = new EmitterSession(taskId, emitter, lastSent);
        CopyOnWriteArrayList<EmitterSession> taskSessions =
                sessions.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (taskSessions) {
            if (taskSessions.size() >= taskProperties.getSseMaxConnectionsPerTask()) {
                activeSessionCount.decrementAndGet();
                if (taskSessions.isEmpty()) {
                    sessions.remove(taskId, taskSessions);
                }
                throw BusinessException.tooManyRequests(
                        "SSE_TASK_CONNECTION_LIMIT", "too many event streams for this task");
            }
            taskSessions.add(session);
        }

        Runnable cleanup = () -> removeSession(taskId, session);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 先登记 session 再回放，随后 Redis/DB 可能重复送达；lastSent 是二次去重边界。
        List<TaskEvent> history = listAfterEventNo(taskId, fromEventNo);
        for (TaskEvent row : history) {
            long eventNo = row.getEventNo() == null ? 0L : row.getEventNo();
            sendEvent(session, eventNo, row.getEventType(), toClientJson(taskId, row));
            lastSent.set(Math.max(lastSent.get(), eventNo));
        }

        if (isTerminal(task.getStatus())) {
            completeSession(session);
            return emitter;
        }

        // Redis 只承载提示性实时消息，订阅失败不丢数据；DB 轮询仍从 lastSent 续读。
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
        session.heartbeat = sseScheduler.scheduleAtFixedRate(
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

        // Redis 宕机、跨实例或漏推时，每秒从持久化事件表续读。
        session.dbPoll = sseScheduler.scheduleAtFixedRate(
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
            if (s.closed.get()) {
                continue;
            }
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
            completeSession(s);
        }
    }

    private void pollDbEvents(EmitterSession session, String workspaceId) {
        if (session.closed.get()) {
            return;
        }
        try {
            long from = session.lastSent.get();
            List<TaskEvent> rows = listAfterEventNo(session.taskId, from);
            for (TaskEvent row : rows) {
                long eventNo = row.getEventNo() == null ? 0L : row.getEventNo();
                if (eventNo <= session.lastSent.get()) {
                    continue;
                }
                sendEvent(session, eventNo, row.getEventType(), toClientJson(session.taskId, row));
                if (session.closed.get()) {
                    return;
                }
                session.lastSent.set(eventNo);
            }
            ResearchTask task = researchTaskMapper.findByIdAndWorkspace(session.taskId, workspaceId);
            if (task != null && isTerminal(task.getStatus())) {
                completeSession(session);
            }
        } catch (Exception ex) {
            log.warn("SSE DB poll failed taskId={}", session.taskId, ex);
        }
    }

    private void dispatchLiveJson(EmitterSession session, String json) throws Exception {
        // Pub/Sub 与 DB 回放可能同时到达，eventNo <= lastSent 的消息必须幂等忽略。
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
        if (session.closed.get()) {
            return;
        }
        if (eventNo > 0) {
            session.lastSent.set(eventNo);
        }
        // TASK_FAILED 是过程事件；只有已持久化的 TASK_RESULT 才是规范终态，否则
        // 过早关闭会让浏览器错过报告版本、质量投影等最终数据。
        if (isTerminalEnvelope(type, map.get("status"))) {
            completeSession(session);
        }
    }

    public static boolean isTerminalEnvelope(String type, Object status) {
        return "TASK_RESULT".equals(type)
                && status != null
                && isTerminal(String.valueOf(status));
    }

    private List<TaskEvent> listAfterEventNo(String taskId, long fromEventNo) {
        return taskEventMapper.listAfterEventNo(taskId, fromEventNo);
    }

    private boolean removeSession(String taskId, EmitterSession session) {
        if (!session.closed.compareAndSet(false, true)) {
            return false;
        }
        activeSessionCount.decrementAndGet();
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
        return true;
    }

    private void sendEvent(EmitterSession session, long eventNo, String type, String json) {
        sendRaw(session, eventNo, type, json);
    }

    private void sendRaw(EmitterSession session, long eventNo, String type, String json) {
        if (session.closed.get()) {
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .id(String.valueOf(eventNo))
                    .name(type == null ? "message" : type)
                    .data(json);
            session.emitter.send(builder);
        } catch (IOException | IllegalStateException ex) {
            // 浏览器关闭 SSE 连接是正常控制流；不要再次 complete 一个已不可用的响应。
            removeSession(session.taskId, session);
        }
    }

    /**
     * 主动结束仍有效的流。先从所有推送来源移除，避免 Redis/轮询并发发送到已完成的响应。
     */
    private void completeSession(EmitterSession session) {
        if (removeSession(session.taskId, session)) {
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
