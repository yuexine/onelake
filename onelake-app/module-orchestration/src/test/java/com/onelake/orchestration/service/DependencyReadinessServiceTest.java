package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineDependency;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineDependencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link DependencyReadinessService} 的同周期、跨周期和运行模式判定测试。 */
@ExtendWith(MockitoExtension.class)
class DependencyReadinessServiceTest {

    @Mock private PipelineDependencyRepository dependencyRepo;
    @Mock private DagRepository dagRepo;
    @Mock private JobRunRepository jobRunRepo;

    @Test
    void pipelineWithoutDependenciesIsReady() {
        Dag downstream = dag("NORMAL", "UTC");
        when(dependencyRepo.findByDownstreamDagIdAndEnabledTrueOrderByCreatedAtAsc(downstream.getId()))
                .thenReturn(List.of());

        boolean ready = service().isReady(
                downstream, Instant.parse("2026-07-10T00:00:00Z"));

        assertThat(ready).isTrue();
        verifyNoInteractions(dagRepo, jobRunRepo);
    }

    @Test
    void dryRunSuccessSatisfiesSameCycleDependency() {
        Dag downstream = dag("NORMAL", "UTC");
        Dag upstream = dag("DRY_RUN", "UTC");
        PipelineDependency dependency = dependency(downstream, upstream, "SAME_CYCLE", null, 0);
        Instant logicalDate = Instant.parse("2026-07-10T00:00:00Z");
        stubDependency(downstream, upstream, dependency);
        when(jobRunRepo.existsByDagIdAndLogicalDateAndStatus(
                upstream.getId(), logicalDate, DagStatus.SUCCEEDED)).thenReturn(true);

        DependencyReadinessService.ReadinessResult result = service().evaluate(downstream, logicalDate);

        assertThat(result.ready()).isTrue();
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void frozenUpstreamBlocksEvenWhenHistoricalSuccessExists() {
        Dag downstream = dag("NORMAL", "UTC");
        Dag upstream = dag("FROZEN", "UTC");
        PipelineDependency dependency = dependency(downstream, upstream, "SAME_CYCLE", null, 0);
        Instant logicalDate = Instant.parse("2026-07-10T00:00:00Z");
        stubDependency(downstream, upstream, dependency);

        DependencyReadinessService.ReadinessResult result = service().evaluate(downstream, logicalDate);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).singleElement()
                .satisfies(blocker -> {
                    assertThat(blocker.upstreamDagId()).isEqualTo(upstream.getId());
                    assertThat(blocker.requiredLogicalDate()).isEqualTo(logicalDate);
                    assertThat(blocker.reason()).isEqualTo("UPSTREAM_FROZEN");
                });
        verify(jobRunRepo, never()).existsByDagIdAndLogicalDateAndStatus(
                upstream.getId(), logicalDate, DagStatus.SUCCEEDED);
    }

    @Test
    void crossCycleDayOffsetUsesDownstreamBusinessTimezone() {
        Dag downstream = dag("NORMAL", "Asia/Shanghai");
        Dag upstream = dag("NORMAL", "Asia/Shanghai");
        PipelineDependency dependency = dependency(downstream, upstream, "CROSS_CYCLE", "DAY", -1);
        Instant downstreamLogicalDate = Instant.parse("2026-07-10T16:00:00Z");
        Instant requiredUpstreamDate = Instant.parse("2026-07-09T16:00:00Z");
        stubDependency(downstream, upstream, dependency);
        when(jobRunRepo.existsByDagIdAndLogicalDateAndStatus(
                upstream.getId(), requiredUpstreamDate, DagStatus.SUCCEEDED)).thenReturn(true);

        DependencyReadinessService.ReadinessResult result = service()
                .evaluate(downstream, downstreamLogicalDate);

        assertThat(result.ready()).isTrue();
        verify(jobRunRepo).existsByDagIdAndLogicalDateAndStatus(
                upstream.getId(), requiredUpstreamDate, DagStatus.SUCCEEDED);
    }

    @Test
    void missingRequiredCycleReturnsWaitingBlocker() {
        Dag downstream = dag("NORMAL", "UTC");
        Dag upstream = dag("NORMAL", "UTC");
        PipelineDependency dependency = dependency(downstream, upstream, "CROSS_CYCLE", "HOUR", -1);
        Instant downstreamLogicalDate = Instant.parse("2026-07-10T10:00:00Z");
        Instant requiredUpstreamDate = Instant.parse("2026-07-10T09:00:00Z");
        stubDependency(downstream, upstream, dependency);
        when(jobRunRepo.existsByDagIdAndLogicalDateAndStatus(
                upstream.getId(), requiredUpstreamDate, DagStatus.SUCCEEDED)).thenReturn(false);

        DependencyReadinessService.ReadinessResult result = service()
                .evaluate(downstream, downstreamLogicalDate);

        assertThat(result.ready()).isFalse();
        assertThat(result.summary())
                .contains(upstream.getId().toString())
                .contains(requiredUpstreamDate.toString())
                .contains("WAITING_UPSTREAM");
    }

    private void stubDependency(Dag downstream,
                                Dag upstream,
                                PipelineDependency dependency) {
        when(dependencyRepo.findByDownstreamDagIdAndEnabledTrueOrderByCreatedAtAsc(downstream.getId()))
                .thenReturn(List.of(dependency));
        when(dagRepo.findByIdAndTenantId(upstream.getId(), downstream.getTenantId()))
                .thenReturn(Optional.of(upstream));
    }

    private DependencyReadinessService service() {
        return new DependencyReadinessService(dependencyRepo, dagRepo, jobRunRepo);
    }

    private Dag dag(String scheduleMode, String timezone) {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(UUID.randomUUID());
        dag.setScheduleMode(scheduleMode);
        dag.setTimezone(timezone);
        return dag;
    }

    private PipelineDependency dependency(Dag downstream,
                                          Dag upstream,
                                          String dependencyType,
                                          String offsetGrain,
                                          int offsetN) {
        upstream.setTenantId(downstream.getTenantId());
        PipelineDependency dependency = new PipelineDependency();
        dependency.setId(UUID.randomUUID());
        dependency.setTenantId(downstream.getTenantId());
        dependency.setDownstreamDagId(downstream.getId());
        dependency.setUpstreamDagId(upstream.getId());
        dependency.setDependencyType(dependencyType);
        dependency.setOffsetGrain(offsetGrain);
        dependency.setOffsetN(offsetN);
        return dependency;
    }
}
