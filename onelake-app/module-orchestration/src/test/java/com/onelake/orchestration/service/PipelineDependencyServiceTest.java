package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineDependency;
import com.onelake.orchestration.dto.PipelineDependencyDTO;
import com.onelake.orchestration.dto.PipelineDependencyRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineDependencyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PipelineDependencyService} 的租户隔离、规范化和成环校验测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineDependencyServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private PipelineDependencyRepository dependencyRepo;
    @Mock private DagRepository dagRepo;

    private PipelineDependencyService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new PipelineDependencyService(dependencyRepo, dagRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsNormalizedCrossCycleDependency() {
        Dag downstream = dag();
        Dag upstream = dag();
        stubDags(downstream, upstream);
        when(dependencyRepo
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstream.getId(), TENANT_ID))
                .thenReturn(List.of());
        when(dependencyRepo.findByTenantIdAndEnabledTrue(TENANT_ID))
                .thenReturn(List.of());
        when(dependencyRepo.saveAndFlush(any(PipelineDependency.class)))
                .thenAnswer(invocation -> {
                    PipelineDependency dependency = invocation.getArgument(0);
                    dependency.setId(UUID.randomUUID());
                    dependency.setCreatedAt(Instant.parse("2026-07-10T12:00:00Z"));
                    return dependency;
                });

        PipelineDependencyDTO result = service.createDependency(
                downstream.getId(),
                new PipelineDependencyRequest(upstream.getId(), "cross_cycle", "day", -1));

        assertThat(result.downstreamDagId()).isEqualTo(downstream.getId());
        assertThat(result.upstreamDagId()).isEqualTo(upstream.getId());
        assertThat(result.dependencyType()).isEqualTo("CROSS_CYCLE");
        assertThat(result.offsetGrain()).isEqualTo("DAY");
        assertThat(result.offsetN()).isEqualTo(-1);
    }

    @Test
    void rejectsTransitiveCycle() {
        Dag downstreamC = dag();
        Dag upstreamA = dag();
        Dag middleB = dag();
        stubDags(downstreamC, upstreamA);
        when(dependencyRepo
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstreamC.getId(), TENANT_ID))
                .thenReturn(List.of());
        when(dependencyRepo.findByTenantIdAndEnabledTrue(TENANT_ID))
                .thenReturn(List.of(
                        dependency(upstreamA.getId(), middleB.getId()),
                        dependency(middleB.getId(), downstreamC.getId())));

        assertThatThrownBy(() -> service.createDependency(
                downstreamC.getId(),
                new PipelineDependencyRequest(
                        upstreamA.getId(), "SAME_CYCLE", null, 0)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40903))
                .hasMessageContaining("环路");

        verify(dependencyRepo, never()).saveAndFlush(any());
    }

    @Test
    void rejectsSameCycleWithOffset() {
        Dag downstream = dag();
        Dag upstream = dag();
        stubDags(downstream, upstream);

        assertThatThrownBy(() -> service.createDependency(
                downstream.getId(),
                new PipelineDependencyRequest(
                        upstream.getId(), "SAME_CYCLE", "DAY", -1)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40023));

        verify(dependencyRepo, never()).saveAndFlush(any());
    }

    @Test
    void listsOnlyRequestedTenantAndDownstream() {
        Dag downstream = dag();
        PipelineDependency dependency = dependency(downstream.getId(), UUID.randomUUID());
        when(dagRepo.findByIdAndTenantId(downstream.getId(), TENANT_ID))
                .thenReturn(Optional.of(downstream));
        when(dependencyRepo
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstream.getId(), TENANT_ID))
                .thenReturn(List.of(dependency));

        List<PipelineDependencyDTO> result = service.listDependencies(downstream.getId());

        assertThat(result).singleElement()
                .extracting(PipelineDependencyDTO::upstreamDagId)
                .isEqualTo(dependency.getUpstreamDagId());
        verify(dependencyRepo)
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstream.getId(), TENANT_ID);
    }

    private void stubDags(Dag downstream, Dag upstream) {
        when(dagRepo.findByIdAndTenantId(downstream.getId(), TENANT_ID))
                .thenReturn(Optional.of(downstream));
        when(dagRepo.findByIdAndTenantId(upstream.getId(), TENANT_ID))
                .thenReturn(Optional.of(upstream));
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(TENANT_ID);
        return dag;
    }

    private PipelineDependency dependency(UUID downstreamDagId, UUID upstreamDagId) {
        PipelineDependency dependency = new PipelineDependency();
        dependency.setId(UUID.randomUUID());
        dependency.setTenantId(TENANT_ID);
        dependency.setDownstreamDagId(downstreamDagId);
        dependency.setUpstreamDagId(upstreamDagId);
        dependency.setDependencyType("SAME_CYCLE");
        dependency.setOffsetN(0);
        dependency.setEnabled(true);
        dependency.setCreatedAt(Instant.parse("2026-07-10T12:00:00Z"));
        return dependency;
    }
}
