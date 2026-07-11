package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.domain.entity.AssetTriggerReceipt;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineSubscription;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.AssetReadinessRepository;
import com.onelake.orchestration.repository.AssetTriggerReceiptRepository;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineSubscriptionRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineSnapshotService;
import com.onelake.orchestration.service.RunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineSyncRefTriggerHandler} 的 P1 单元测试。
 *
 * <p>验证编排侧事件处理器会用 {@code SYNC_REF.target_fqn} 匹配事件载荷中的
 * {@code targetTable}，并通过 {@link OrchestrationService#triggerPipelineRun}
 * 触发正确流水线。
 */
@ExtendWith(MockitoExtension.class)
class PipelineSyncRefTriggerHandlerTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineSubscriptionRepository subscriptionRepo;
    @Mock private AssetReadinessRepository readinessRepo;
    @Mock private AssetTriggerReceiptRepository triggerReceiptRepo;
    @Mock private SchedulerLockRepository schedulerLockRepo;
    @Mock private PipelineSnapshotService pipelineSnapshotService;
    @Mock private OrchestrationService orchestrationService;
    @Mock private SyncRunSucceededEventHandler legacySyncRunHandler;

    private PipelineSyncRefTriggerHandler handler;
    private List<AssetReadiness> readinessState;

    @BeforeEach
    void setup() {
        readinessState = new ArrayList<>();
        handler = new PipelineSyncRefTriggerHandler(
                dagRepo,
                subscriptionRepo,
                readinessRepo,
                triggerReceiptRepo,
                schedulerLockRepo,
                pipelineSnapshotService,
                orchestrationService,
                legacySyncRunHandler);
        lenient().when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(subscriptionRepo.findByDagIdAndEnabledTrue(any())).thenReturn(List.of());
        lenient().when(schedulerLockRepo.acquire(any(), any(), any())).thenReturn(1);
        lenient().when(schedulerLockRepo.release(any(), any())).thenReturn(1);
        lenient().when(triggerReceiptRepo.existsByDagIdAndTriggerKeyAndStatus(
                        any(), any(), eq("TRIGGERED")))
                .thenReturn(false);
        lenient().when(triggerReceiptRepo.saveAllAndFlush(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        lenient().when(readinessRepo.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<AssetReadiness> updates = invocation.getArgument(0);
            for (AssetReadiness update : updates) {
                readinessState.removeIf(existing -> existing.getDagId().equals(update.getDagId())
                        && existing.getTaskKey().equals(update.getTaskKey()));
                readinessState.add(update);
            }
            return updates;
        });
        lenient().when(readinessRepo.findByDagId(any())).thenAnswer(invocation -> {
            UUID dagId = invocation.getArgument(0);
            return readinessState.stream()
                    .filter(readiness -> dagId.equals(readiness.getDagId()))
                    .toList();
        });
        lenient().doAnswer(invocation -> {
            List<AssetReadiness> stale = invocation.getArgument(0);
            readinessState.removeAll(stale);
            return null;
        }).when(readinessRepo).deleteAllInBatch(any());
        lenient().doAnswer(invocation -> {
            UUID dagId = invocation.getArgument(0);
            readinessState.removeIf(readiness -> dagId.equals(readiness.getDagId()));
            return null;
        }).when(readinessRepo).deleteByDagId(any());
        lenient().doAnswer(invocation -> {
            UUID dagId = invocation.getArgument(0);
            List<String> taskKeys = invocation.getArgument(1);
            readinessState.removeIf(readiness -> dagId.equals(readiness.getDagId())
                    && taskKeys.contains(readiness.getTaskKey()));
            return null;
        }).when(readinessRepo).deleteByDagIdAndTaskKeyIn(any(), any());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void subscribesToBothAssetLoadedEvents() {
        assertThat(handler.eventTypes())
                .isEqualTo(Set.of(
                        DomainEvents.INTEGRATION_TABLE_LOADED,
                        DomainEvents.PIPELINE_TASK_LOADED));
    }

    @Test
    void triggersMatchingPipelineAndSetsTenantContext() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask syncRef = syncRefTask(tenantId, dagId, "iceberg.ods.orders");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(syncRef), List.of());

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        // 触发期间会设置租户上下文，触发后必须恢复为空。
        assertThat(TenantContext.getTenantId()).isNull();
        verify(legacySyncRunHandler).handle(any(OutboxEvent.class));
    }

    @Test
    void waitsForAllSyncRefInputsBeforeTriggeringFanInPipeline() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right")));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user"));
        assertThat(readinessState)
                .extracting(AssetReadiness::getTaskKey)
                .containsExactly("sync_user");
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile"));
        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        assertThat(readinessState).isEmpty();
    }

    @Test
    void latestPolicyTriggersWhenAnyFanInInputArrives() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left", "LATEST"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right", "LATEST")));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user"));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        assertThat(readinessState).isEmpty();
    }

    @Test
    void edgePolicyTakesPrecedenceWhenEventAlsoMatchesSubscription() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        String userAsset = "iceberg.ods.user";
        String profileAsset = "iceberg.ods.user_profile";
        PipelineTask user = syncRefTask(tenantId, dagId, userAsset, "sync_user");
        PipelineTask profile = syncRefTask(tenantId, dagId, profileAsset, "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left", "LATEST"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right", "LATEST")));
        PipelineSubscription userSubscription = subscription(
                tenantId, dagId, "ASSET", userAsset, "SAME_BATCH");
        PipelineSubscription profileSubscription = subscription(
                tenantId, dagId, "ASSET", profileAsset, "SAME_BATCH");
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "ASSET", userAsset)).thenReturn(List.of(userSubscription));
        lenient().when(subscriptionRepo.findByDagIdAndEnabledTrue(dagId))
                .thenReturn(List.of(userSubscription, profileSubscription));

        handler.handle(tableLoadedEvent(tenantId, userAsset, "batch-1", null, null));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        verify(subscriptionRepo, never()).findByDagIdAndEnabledTrue(dagId);
    }

    @Test
    void sameBatchRejectsMixedInputsAndTriggersAfterSameBatchArrives() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left", "SAME_BATCH"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right", "SAME_BATCH")));
        Set<String> receipts = statefulTriggerReceipts();

        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user", "batch-1", null, null));
        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user_profile", "batch-2", null, null));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
        assertThat(readinessState).isEmpty();

        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user_profile", "batch-1", null, null));
        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user", "batch-1", null, null));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        assertThat(readinessState).isEmpty();
        assertThat(receipts).hasSize(1);
    }

    @Test
    void sameBatchKeepsDifferentBatchesDistinctWithinOneLogicalWindow() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask consume = sparkTask(tenantId, dagId, "spark_consume");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, consume), List.of(
                edge(tenantId, dagId, "sync_user", "spark_consume", "left", "LATEST"),
                edge(tenantId, dagId, "sync_profile", "spark_consume", "right", "SAME_BATCH")));
        Instant logicalDate = Instant.parse("2026-07-10T00:00:00Z");
        statefulTriggerReceipts();

        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user", "batch-1", logicalDate, null));
        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user_profile", "batch-1", logicalDate, null));
        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user", "batch-2", logicalDate, null));
        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user_profile", "batch-2", logicalDate, null));

        ArgumentCaptor<String> triggerKeys = ArgumentCaptor.forClass(String.class);
        verify(orchestrationService, times(2)).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId),
                triggerKeys.capture());
        assertThat(triggerKeys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void doesNotMixFanInReadinessAcrossFreshnessWindows() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right")));
        Instant firstWindow = Instant.parse("2026-07-10T00:00:00Z");
        Instant secondWindow = Instant.parse("2026-07-11T00:00:00Z");
        Set<String> receipts = statefulTriggerReceipts();

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user", firstWindow));
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile", secondWindow));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
        assertThat(readinessState).isEmpty();

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile", firstWindow));
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user", firstWindow));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        assertThat(receipts).hasSize(1);
    }

    @Test
    void missingBatchFallsBackToLatestInsteadOfUsingRunId() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left", "SAME_BATCH"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right", "SAME_BATCH")));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user"));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        ArgumentCaptor<List<AssetReadiness>> saved = ArgumentCaptor.forClass(List.class);
        verify(readinessRepo).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).extracting(AssetReadiness::getBatchId).containsOnlyNulls();
    }

    @Test
    void missingFreshnessWindowFallsBackToLatest() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left", "SAME_FRESHNESS_WINDOW"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right", "SAME_FRESHNESS_WINDOW")));

        handler.handle(tableLoadedEvent(
                tenantId, "iceberg.ods.user", null, null, null));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
    }

    @Test
    void republishRemovesIncompleteBarrierFromPreviousVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        List<PipelineTaskEdge> edges = List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right"));
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        stubPublishedPipeline(dag, List.of(user, profile, join), edges);

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user"));
        assertThat(readinessState).hasSize(1);

        stubPublishedPipeline(dag, List.of(user, profile, join), edges);
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile"));

        assertThat(readinessState)
                .extracting(AssetReadiness::getTaskKey)
                .containsExactly("sync_profile");
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void disablingPipelineClearsPersistedBarrierBeforeReenable() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask user = syncRefTask(tenantId, dagId, "iceberg.ods.user", "sync_user");
        PipelineTask profile = syncRefTask(
                tenantId, dagId, "iceberg.ods.user_profile", "sync_profile");
        PipelineTask join = sparkTask(tenantId, dagId, "spark_join");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        stubPublishedPipeline(dag, List.of(user, profile, join), List.of(
                edge(tenantId, dagId, "sync_user", "spark_join", "left"),
                edge(tenantId, dagId, "sync_profile", "spark_join", "right")));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user"));
        assertThat(readinessState).hasSize(1);

        dag.setEnabled(false);
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.nonexistent"));
        assertThat(readinessState).isEmpty();

        dag.setEnabled(true);
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile"));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void triggersExplicitAssetSubscriptionWithoutSyncRef() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        String assetFqn = "iceberg.ods.orders";
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(
                dag, List.of(sparkTask(tenantId, dagId, "spark_orders")), List.of());
        PipelineSubscription subscription = subscription(
                tenantId, dagId, "ASSET", assetFqn);
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "ASSET", assetFqn)).thenReturn(List.of(subscription));

        handler.handle(tableLoadedEvent(tenantId, assetFqn));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
    }

    @Test
    void sameBatchPolicyFromSubscriptionsWaitsForAllSources() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        String orders = "iceberg.ods.orders";
        String customers = "iceberg.ods.customers";
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(
                dag, List.of(sparkTask(tenantId, dagId, "spark_consumer")), List.of());
        PipelineSubscription ordersSubscription = subscription(
                tenantId, dagId, "ASSET", orders, "SAME_BATCH");
        PipelineSubscription customersSubscription = subscription(
                tenantId, dagId, "ASSET", customers, "SAME_BATCH");
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "ASSET", orders)).thenReturn(List.of(ordersSubscription));
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "ASSET", customers)).thenReturn(List.of(customersSubscription));
        when(subscriptionRepo.findByDagIdAndEnabledTrue(dagId))
                .thenReturn(List.of(ordersSubscription, customersSubscription));

        handler.handle(tableLoadedEvent(tenantId, orders, "batch-1", null, null));
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());

        handler.handle(tableLoadedEvent(tenantId, customers, "batch-1", null, null));

        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
        assertThat(readinessState).isEmpty();
    }

    @Test
    void triggersExplicitPipelineSubscriptionAndPropagatesLogicalDate() {
        UUID tenantId = UUID.randomUUID();
        UUID upstreamDagId = UUID.randomUUID();
        UUID downstreamDagId = UUID.randomUUID();
        String assetFqn = "iceberg.dwd.orders";
        Instant logicalDate = Instant.parse("2026-07-10T00:00:00Z");
        Dag downstream = pipelineDag(tenantId, downstreamDagId, "VALIDATED", true);
        UUID versionId = stubPublishedPipeline(
                downstream,
                List.of(sparkTask(tenantId, downstreamDagId, "spark_consumer")),
                List.of());
        PipelineSubscription subscription = subscription(
                tenantId, downstreamDagId, "PIPELINE", upstreamDagId.toString());
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "PIPELINE", upstreamDagId.toString())).thenReturn(List.of(subscription));

        handler.handle(pipelineTaskLoadedEvent(
                tenantId, upstreamDagId, assetFqn, "batch-20260710", logicalDate));

        verify(orchestrationService).triggerPipelineRun(
                eq(downstreamDagId),
                eq(TriggerType.EVENT),
                org.mockito.ArgumentMatchers.<RunContext>argThat(
                        context -> logicalDate.equals(context.logicalDate())),
                eq(versionId),
                anyString());
        verify(legacySyncRunHandler, never()).handle(any());
    }

    @Test
    void deduplicatesSamePipelineSourceAndLogicalDateAfterLockRelease() {
        UUID tenantId = UUID.randomUUID();
        UUID upstreamDagId = UUID.randomUUID();
        UUID downstreamDagId = UUID.randomUUID();
        Instant logicalDate = Instant.parse("2026-07-10T00:00:00Z");
        Dag downstream = pipelineDag(tenantId, downstreamDagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(
                downstream,
                List.of(sparkTask(tenantId, downstreamDagId, "spark_consumer")),
                List.of());
        PipelineSubscription subscription = subscription(
                tenantId, downstreamDagId, "PIPELINE", upstreamDagId.toString());
        when(subscriptionRepo.findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                tenantId, "PIPELINE", upstreamDagId.toString())).thenReturn(List.of(subscription));
        when(triggerReceiptRepo.existsByDagIdAndTriggerKeyAndStatus(
                eq(downstreamDagId), any(), eq("TRIGGERED")))
                .thenReturn(false, true);

        handler.handle(pipelineTaskLoadedEvent(
                tenantId, upstreamDagId, "iceberg.dwd.orders", "batch-1", logicalDate));
        handler.handle(pipelineTaskLoadedEvent(
                tenantId, upstreamDagId, "iceberg.dwd.customers", "batch-2", logicalDate));

        verify(orchestrationService, times(1)).triggerPipelineRun(
                eq(downstreamDagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(versionId), anyString());
        verify(triggerReceiptRepo, times(1)).saveAllAndFlush(any());
    }

    @Test
    void replicaLosingSchedulerLockDoesNotDuplicateTrigger() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask syncRef = syncRefTask(tenantId, dagId, "iceberg.ods.orders");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(syncRef), List.of());
        when(schedulerLockRepo.acquire(any(), any(), any())).thenReturn(1, 0);
        PipelineSyncRefTriggerHandler secondReplica = new PipelineSyncRefTriggerHandler(
                dagRepo,
                subscriptionRepo,
                readinessRepo,
                triggerReceiptRepo,
                schedulerLockRepo,
                pipelineSnapshotService,
                orchestrationService,
                legacySyncRunHandler);
        OutboxEvent event = tableLoadedEvent(tenantId, "iceberg.ods.orders");

        handler.handle(event);
        assertThatThrownBy(() -> secondReplica.handle(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("正由其他副本处理资产触发");

        verify(orchestrationService, times(1)).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId), anyString());
    }

    @Test
    void skipsNonMatchingSyncRefTasks() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        stubPublishedPipeline(dag, List.of(
                syncRefTask(tenantId, dagId, "iceberg.ods.orders"),
                syncRefTask(tenantId, dagId, "iceberg.ods.users")), List.of());

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.nonexistent"));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void skipsDisabledPipeline() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", false);  // 已禁用，应该跳过。
        dag.setPublishedVersionId(UUID.randomUUID());
        when(dagRepo.findByTenantId(tenantId)).thenReturn(List.of(dag));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void skipsPipelineInDraftStatus() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        Dag dag = pipelineDag(tenantId, dagId, "VALIDATED", true);  // 无发布版本的生产事件必须跳过。
        when(dagRepo.findByTenantId(tenantId)).thenReturn(List.of(dag));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void skipsPublishedPipelineWithoutVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        dag.setPublishedVersionId(null);
        when(dagRepo.findByTenantId(tenantId)).thenReturn(List.of(dag));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));

        verify(pipelineSnapshotService, never())
                .loadExecutionSnapshot(any(UUID.class), any(UUID.class));
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any(), anyString());
    }

    @Test
    void swallowsBizExceptionFromTriggerService() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        PipelineTask syncRef = syncRefTask(tenantId, dagId, "iceberg.ods.orders");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(syncRef), List.of());
        doThrow(new BizException(40060, "compile failed"))
                .when(orchestrationService).triggerPipelineRun(
                        eq(dagId), eq(TriggerType.EVENT), any(RunContext.class),
                        eq(versionId), anyString());

        // 业务异常不应向外抛出，避免 Outbox 后台消费被单条事件打断。
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));
    }

    @Test
    void retriesReceiptFailureWithStableJobRunIdempotencyKey() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask syncRef = syncRefTask(tenantId, dagId, "iceberg.ods.orders");
        Dag dag = pipelineDag(tenantId, dagId, "PUBLISHED", true);
        UUID versionId = stubPublishedPipeline(dag, List.of(syncRef), List.of());
        OutboxEvent event = tableLoadedEvent(tenantId, "iceberg.ods.orders");
        when(orchestrationService.triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(versionId), anyString())).thenReturn(runId);
        when(triggerReceiptRepo.saveAllAndFlush(any()))
                .thenThrow(new IllegalStateException("receipt unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("回执持久化失败");

        handler.handle(event);

        ArgumentCaptor<String> triggerKey = ArgumentCaptor.forClass(String.class);
        verify(orchestrationService, times(2)).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(versionId), triggerKey.capture());
        assertThat(triggerKey.getAllValues()).containsOnly(triggerKey.getValue());
    }

    @Test
    void skipsEventWithMissingTargetTable() {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setPayload("{\"tenantId\":\"" + UUID.randomUUID() + "\"}");
        event.setOccurredAt(Instant.now());

        handler.handle(event);

        verify(dagRepo, never()).findByTenantId(any());
    }

    // ---------- 辅助方法 ----------

    private UUID stubPublishedPipeline(Dag dag,
                                       List<PipelineTask> tasks,
                                       List<PipelineTaskEdge> edges) {
        UUID versionId = UUID.randomUUID();
        dag.setPublishedVersionId(versionId);
        PipelineVersion version = new PipelineVersion();
        version.setId(versionId);
        version.setDagId(dag.getId());
        version.setTenantId(dag.getTenantId());
        version.setCreatedAt(Instant.now());
        when(dagRepo.findByTenantId(dag.getTenantId())).thenReturn(List.of(dag));
        lenient().when(pipelineSnapshotService.loadExecutionSnapshot(versionId, dag.getId()))
                .thenReturn(new PipelineSnapshotService.ExecutionSnapshot(
                        version, dag, tasks, edges, List.of()));
        return versionId;
    }

    private OutboxEvent tableLoadedEvent(UUID tenantId, String targetTable) {
        return tableLoadedEvent(
                tenantId, targetTable, Instant.parse("2026-07-10T00:00:00Z"));
    }

    private OutboxEvent tableLoadedEvent(UUID tenantId,
                                         String targetTable,
                                         Instant logicalDate) {
        return tableLoadedEvent(tenantId, targetTable, null, logicalDate, null);
    }

    private OutboxEvent tableLoadedEvent(UUID tenantId,
                                         String targetTable,
                                         String batchId,
                                         Instant logicalDate,
                                         String freshnessWindow) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantId", tenantId.toString());
            payload.put("targetTable", targetTable);
            payload.put("runId", UUID.randomUUID().toString());
            payload.put("status", "SUCCEEDED");
            if (batchId != null) {
                payload.put("batchId", batchId);
            }
            if (logicalDate != null) {
                payload.put("logicalDate", logicalDate.toString());
            }
            if (freshnessWindow != null) {
                payload.put("freshnessWindow", freshnessWindow);
            }
            e.setPayload(new ObjectMapper().writeValueAsString(payload));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        e.setEventType(DomainEvents.INTEGRATION_TABLE_LOADED);
        e.setOccurredAt(Instant.now());
        return e;
    }

    private OutboxEvent pipelineTaskLoadedEvent(UUID tenantId,
                                                UUID pipelineId,
                                                String targetFqn,
                                                String batchId,
                                                Instant logicalDate) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        try {
            event.setPayload(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "tenantId", tenantId.toString(),
                    "pipelineId", pipelineId.toString(),
                    "targetFqn", targetFqn,
                    "batchId", batchId,
                    "logicalDate", logicalDate.toString()
            )));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        event.setEventType(DomainEvents.PIPELINE_TASK_LOADED);
        event.setOccurredAt(Instant.now());
        return event;
    }

    private PipelineSubscription subscription(UUID tenantId,
                                              UUID dagId,
                                              String sourceType,
                                              String sourceRef) {
        return subscription(tenantId, dagId, sourceType, sourceRef, "LATEST");
    }

    private PipelineSubscription subscription(UUID tenantId,
                                              UUID dagId,
                                              String sourceType,
                                              String sourceRef,
                                              String freshnessPolicy) {
        PipelineSubscription subscription = new PipelineSubscription();
        subscription.setId(UUID.randomUUID());
        subscription.setTenantId(tenantId);
        subscription.setDagId(dagId);
        subscription.setSourceType(sourceType);
        subscription.setSourceRef(sourceRef);
        subscription.setFreshnessPolicy(freshnessPolicy);
        subscription.setEnabled(true);
        return subscription;
    }

    private Set<String> statefulTriggerReceipts() {
        Set<String> receipts = new HashSet<>();
        when(triggerReceiptRepo.existsByDagIdAndTriggerKeyAndStatus(
                any(), any(), eq("TRIGGERED")))
                .thenAnswer(invocation -> receipts.contains(invocation.getArgument(1)));
        when(triggerReceiptRepo.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<AssetTriggerReceipt> saved = invocation.getArgument(0);
            saved.stream().map(AssetTriggerReceipt::getTriggerKey).forEach(receipts::add);
            return saved;
        });
        return receipts;
    }

    private PipelineTask syncRefTask(UUID tenantId, UUID dagId, String targetFqn) {
        return syncRefTask(tenantId, dagId, targetFqn, "sync_ref_" + UUID.randomUUID());
    }

    private PipelineTask syncRefTask(UUID tenantId, UUID dagId, String targetFqn, String taskKey) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(taskKey);
        t.setTaskType(TaskType.SYNC_REF);
        t.setTargetFqn(targetFqn);
        return t;
    }

    private PipelineTask sparkTask(UUID tenantId, UUID dagId, String taskKey) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(taskKey);
        t.setTaskType(TaskType.SPARK_SQL);
        t.setTargetFqn("iceberg.dwd.user_join");
        return t;
    }

    private PipelineTaskEdge edge(UUID tenantId,
                                  UUID dagId,
                                  String sourceKey,
                                  String targetKey,
                                  String targetInput) {
        return edge(tenantId, dagId, sourceKey, targetKey, targetInput,
                "SAME_FRESHNESS_WINDOW");
    }

    private PipelineTaskEdge edge(UUID tenantId,
                                  UUID dagId,
                                  String sourceKey,
                                  String targetKey,
                                  String targetInput,
                                  String freshnessPolicy) {
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setTenantId(tenantId);
        edge.setDagId(dagId);
        edge.setSourceKey(sourceKey);
        edge.setTargetKey(targetKey);
        edge.setEdgeLayer(EdgeLayer.PIPELINE);
        edge.setSourcePort("out");
        edge.setTargetPort(targetInput);
        edge.setSourceOutput("out");
        edge.setTargetInput(targetInput);
        edge.setTriggerPolicy("ALL_SUCCEEDED");
        edge.setFreshnessPolicy(freshnessPolicy);
        return edge;
    }

    private Dag pipelineDag(UUID tenantId, UUID dagId, String status, boolean enabled) {
        Dag d = new Dag();
        d.setId(dagId);
        d.setTenantId(tenantId);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setStatus(status);
        d.setEnabled(enabled);
        return d;
    }
}
