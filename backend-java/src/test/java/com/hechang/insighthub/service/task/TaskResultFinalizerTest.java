package com.hechang.insighthub.service.task;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.service.TaskResultService;

@ExtendWith(MockitoExtension.class)
class TaskResultFinalizerTest {

    @Mock private ResearchTaskMapper taskMapper;
    @Mock private TaskResultService taskResultService;
    @Mock private TaskStateMachine stateMachine;
    @Mock private TaskEventService taskEventService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private TaskResultFinalizer finalizer;

    @Test
    void staleRunResultCannotOverwriteCurrentRetry() throws Exception {
        ResearchTask task = new ResearchTask();
        task.setStatus("RUNNING");
        task.setCurrentRunId("run-new");
        when(taskMapper.findByIdAndWorkspaceForUpdate("task-1", "workspace-1")).thenReturn(task);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        var result = new ObjectMapper().readTree("""
                {"status":"COMPLETED","runId":"run-old","reportMarkdown":"stale"}
                """);

        assertNull(finalizer.finalizeResult("task-1", "workspace-1", result));

        verifyNoInteractions(taskResultService, taskEventService, stateMachine);
        verify(taskMapper, never()).updateTaskFinished(any(), any(), any(), any(), any(), any());
    }
}
