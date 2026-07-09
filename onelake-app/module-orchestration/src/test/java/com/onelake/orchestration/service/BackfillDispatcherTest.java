package com.onelake.orchestration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BackfillDispatcherTest {

    private static final UUID BACKFILL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private BackfillService backfillService;

    @Mock
    private TaskExecutor taskExecutor;

    @Test
    void dispatchNowSubmitsWorkWithoutDispatchingOnCallerThread() {
        BackfillDispatcher dispatcher = new BackfillDispatcher(backfillService, taskExecutor);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        dispatcher.dispatchNow(BACKFILL_ID);

        verify(taskExecutor).execute(taskCaptor.capture());
        verify(backfillService, never()).dispatchBackfill(BACKFILL_ID);

        taskCaptor.getValue().run();

        verify(backfillService).dispatchBackfill(BACKFILL_ID);
    }

    @Test
    void dispatchNowFallsBackToScheduledRecoveryWhenExecutorIsFull() {
        BackfillDispatcher dispatcher = new BackfillDispatcher(backfillService, taskExecutor);
        doThrow(new TaskRejectedException("queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertThatCode(() -> dispatcher.dispatchNow(BACKFILL_ID)).doesNotThrowAnyException();

        verify(backfillService, never()).dispatchBackfill(BACKFILL_ID);
    }
}
