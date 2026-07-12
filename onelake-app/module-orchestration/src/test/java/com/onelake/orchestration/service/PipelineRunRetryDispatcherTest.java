package com.onelake.orchestration.service;

import com.onelake.orchestration.repository.SchedulerLockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link PipelineRunRetryDispatcher} 的无人值守状态同步顺序测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineRunRetryDispatcherTest {

    @Mock private PipelineRunRetryService retryService;
    @Mock private SchedulerLockRepository schedulerLockRepo;

    @Test
    void refreshesActiveRunsBeforeLoadingDueFailures() {
        UUID activeRunId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        when(schedulerLockRepo.acquire(anyString(), anyString(), any(Instant.class))).thenReturn(1);
        when(schedulerLockRepo.release(anyString(), anyString())).thenReturn(1);
        when(retryService.activeRunIds()).thenReturn(List.of(activeRunId));
        when(retryService.retryCandidateIds(any(Instant.class))).thenReturn(List.of(failedRunId));
        PipelineRunRetryDispatcher dispatcher = new PipelineRunRetryDispatcher(
                retryService, schedulerLockRepo);

        dispatcher.tickRetries();

        InOrder order = inOrder(retryService);
        order.verify(retryService).activeRunIds();
        order.verify(retryService).refreshRunStatus(activeRunId);
        order.verify(retryService).retryCandidateIds(any(Instant.class));
        order.verify(retryService).retryIfDue(eq(failedRunId), any(Instant.class));
        verify(schedulerLockRepo).release(anyString(), anyString());
    }

    @Test
    void replicaWithoutStatusSyncLockSkipsScan() {
        when(schedulerLockRepo.acquire(anyString(), anyString(), any(Instant.class))).thenReturn(0);
        PipelineRunRetryDispatcher dispatcher = new PipelineRunRetryDispatcher(
                retryService, schedulerLockRepo);

        dispatcher.tickRetries();

        verifyNoInteractions(retryService);
        verify(schedulerLockRepo, never()).release(anyString(), anyString());
    }
}
