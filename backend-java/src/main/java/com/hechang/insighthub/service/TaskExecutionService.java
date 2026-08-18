package com.hechang.insighthub.service;

/**
 * 异步消费 Python NDJSON 流并落库 / 推送。
 */
public interface TaskExecutionService {

    /**
     * 异步执行流式任务。
     *
     * @param taskId      任务 ID
     * @param workspaceId 工作空间 ID
     * @param userId      创建者 ID
     * @param query       研究问题
     * @param traceId     链路追踪 ID
     * @param resume      是否为恢复（resume）调用
     */
    void executeStream(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            boolean resume);

    /**
     * Consume one durable outbox command.  The outbox executor, rather than
     * this service, owns the asynchronous boundary so it can acknowledge only
     * after this method has returned.
     */
    void executeDispatch(TaskDispatchCommand command);
}
