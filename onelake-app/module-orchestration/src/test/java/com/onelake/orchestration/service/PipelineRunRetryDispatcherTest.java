package com.onelake.orchestration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/** {@link PipelineRunRetryDispatcher} 的无人值守状态同步顺序测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineRunRetryDispatcherTest {

    @Mock private PipelineRunRetryService retryService;

    @Test
    void refreshesActiveRunsBeforeLoadingDueFailures() {
        UUID activeRunId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        when(retryService.retryWatchRunIds()).thenReturn(List.of(activeRunId));
        when(retryService.retryCandidateIds(any(Instant.class))).thenReturn(List.of(failedRunId));
        PipelineRunRetryDispatcher dispatcher = new PipelineRunRetryDispatcher(retryService);

        dispatcher.tickRetries();

        InOrder order = inOrder(retryService);
        order.verify(retryService).retryWatchRunIds();
        order.verify(retryService).refreshRunStatus(activeRunId);
        order.verify(retryService).retryCandidateIds(any(Instant.class));
        order.verify(retryService).retryIfDue(eq(failedRunId), any(Instant.class));
    }
}
