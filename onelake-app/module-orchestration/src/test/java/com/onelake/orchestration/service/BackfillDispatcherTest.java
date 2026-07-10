package com.onelake.orchestration.service;

import com.onelake.orchestration.dto.BackfillDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void dispatchCatchupPersistsExactIntervalsBeforeAsyncDispatch() {
        BackfillDispatcher dispatcher = new BackfillDispatcher(backfillService, taskExecutor);
        List<DataIntervalCalculator.DataInterval> intervals = List.of(
                new DataIntervalCalculator.DataInterval(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z")));
        BackfillDTO created = new BackfillDTO(
                BACKFILL_ID,
                UUID.randomUUID(),
                "QUEUED",
                1,
                0,
                0,
                2,
                new BackfillDTO.Range(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z")),
                "DAY",
                "UTC",
                Instant.parse("2026-01-05T00:00:00Z"),
                Instant.parse("2026-01-05T00:00:00Z"),
                List.of());
        when(backfillService.createCatchupBackfill(
                created.dagId(), intervals, "DAY", 2)).thenReturn(created);

        UUID result = dispatcher.dispatchCatchup(created.dagId(), intervals, "DAY", 2);

        assertThat(result).isEqualTo(BACKFILL_ID);
        verify(taskExecutor).execute(any(Runnable.class));
    }
}
