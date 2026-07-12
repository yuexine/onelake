package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineSubscription;
import com.onelake.orchestration.dto.PipelineSubscriptionDTO;
import com.onelake.orchestration.dto.PipelineSubscriptionRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

/** {@link PipelineSubscriptionService} 的租户隔离、规范化与归属校验测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineSubscriptionServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private PipelineSubscriptionRepository subscriptionRepo;
    @Mock private DagRepository dagRepo;

    private PipelineSubscriptionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new PipelineSubscriptionService(subscriptionRepo, dagRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsOnlyRequestedTenantAndDag() {
        Dag downstream = dag();
        PipelineSubscription subscription = subscription(downstream.getId());
        when(dagRepo.findByIdAndTenantId(downstream.getId(), TENANT_ID))
                .thenReturn(Optional.of(downstream));
        when(subscriptionRepo.findByDagIdAndTenantIdOrderByCreatedAtAsc(
                downstream.getId(), TENANT_ID))
                .thenReturn(List.of(subscription));

        List<PipelineSubscriptionDTO> result = service.list(downstream.getId());

        assertThat(result).singleElement()
                .extracting(PipelineSubscriptionDTO::sourceRef)
                .isEqualTo("onelake.ods.orders");
    }

    @Test
    void createsNormalizedQualityGatedAssetSubscription() {
        Dag downstream = dag();
        stubDag(downstream);
        when(subscriptionRepo.existsByDagIdAndSourceTypeAndSourceRef(
                downstream.getId(), "ASSET", "onelake.ods.orders"))
                .thenReturn(false);
        when(subscriptionRepo.saveAndFlush(any(PipelineSubscription.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        PipelineSubscriptionDTO result = service.create(
                downstream.getId(),
                new PipelineSubscriptionRequest(
                        "asset", " onelake.ods.orders ",
                        "on_update_and_quality_pass", "same_batch"));

        assertThat(result.sourceType()).isEqualTo("ASSET");
        assertThat(result.sourceRef()).isEqualTo("onelake.ods.orders");
        assertThat(result.condition()).isEqualTo("ON_UPDATE_AND_QUALITY_PASS");
        assertThat(result.freshnessPolicy()).isEqualTo("SAME_BATCH");
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void acceptsAssetSourceReferenceUpToCurrentSchemaLimit() {
        Dag downstream = dag();
        String longFqn = "catalog.schema." + "asset".repeat(70);
        stubDag(downstream);
        when(subscriptionRepo.existsByDagIdAndSourceTypeAndSourceRef(
                downstream.getId(), "ASSET", longFqn))
                .thenReturn(false);
        when(subscriptionRepo.saveAndFlush(any(PipelineSubscription.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        PipelineSubscriptionDTO result = service.create(
                downstream.getId(),
                new PipelineSubscriptionRequest(
                        "ASSET", longFqn, "ON_UPDATE", "LATEST"));

        assertThat(longFqn.length()).isBetween(257, 512);
        assertThat(result.sourceRef()).isEqualTo(longFqn);
    }

    @Test
    void validatesAndNormalizesUpstreamPipeline() {
        Dag downstream = dag();
        Dag upstream = dag();
        stubDag(downstream);
        stubDag(upstream);
        when(subscriptionRepo.existsByDagIdAndSourceTypeAndSourceRef(
                downstream.getId(), "PIPELINE", upstream.getId().toString()))
                .thenReturn(false);
        when(subscriptionRepo.saveAndFlush(any(PipelineSubscription.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        service.create(downstream.getId(), new PipelineSubscriptionRequest(
                "PIPELINE", upstream.getId().toString().toUpperCase(),
                "ON_UPDATE", "LATEST"));

        ArgumentCaptor<PipelineSubscription> captor =
                ArgumentCaptor.forClass(PipelineSubscription.class);
        verify(subscriptionRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSourceRef()).isEqualTo(upstream.getId().toString());
    }

    @Test
    void rejectsSelfSubscription() {
        Dag downstream = dag();
        stubDag(downstream);

        assertThatThrownBy(() -> service.create(
                downstream.getId(), new PipelineSubscriptionRequest(
                        "PIPELINE", downstream.getId().toString(),
                        "ON_UPDATE", "LATEST")))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode())
                        .isEqualTo(40084));

        verify(subscriptionRepo, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateSource() {
        Dag downstream = dag();
        stubDag(downstream);
        when(subscriptionRepo.existsByDagIdAndSourceTypeAndSourceRef(
                downstream.getId(), "ASSET", "onelake.ods.orders"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                downstream.getId(), new PipelineSubscriptionRequest(
                        "ASSET", "onelake.ods.orders", "ON_UPDATE", "LATEST")))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode())
                        .isEqualTo(40904));
    }

    @Test
    void deletesOnlyRequestedTenantAndDag() {
        Dag downstream = dag();
        PipelineSubscription subscription = subscription(downstream.getId());
        stubDag(downstream);
        when(subscriptionRepo.findByIdAndDagIdAndTenantId(
                subscription.getId(), downstream.getId(), TENANT_ID))
                .thenReturn(Optional.of(subscription));

        service.delete(downstream.getId(), subscription.getId());

        verify(subscriptionRepo).delete(subscription);
    }

    private void stubDag(Dag dag) {
        when(dagRepo.findByIdAndTenantId(dag.getId(), TENANT_ID))
                .thenReturn(Optional.of(dag));
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(TENANT_ID);
        return dag;
    }

    private PipelineSubscription subscription(UUID dagId) {
        PipelineSubscription subscription = new PipelineSubscription();
        subscription.setId(UUID.randomUUID());
        subscription.setTenantId(TENANT_ID);
        subscription.setDagId(dagId);
        subscription.setSourceType("ASSET");
        subscription.setSourceRef("onelake.ods.orders");
        subscription.setCondition("ON_UPDATE");
        subscription.setFreshnessPolicy("LATEST");
        subscription.setEnabled(true);
        subscription.setCreatedAt(Instant.parse("2026-07-12T00:00:00Z"));
        return subscription;
    }

    private PipelineSubscription saved(PipelineSubscription subscription) {
        subscription.setId(UUID.randomUUID());
        subscription.setCreatedAt(Instant.parse("2026-07-12T00:00:00Z"));
        return subscription;
    }
}
