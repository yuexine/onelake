package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineSnapshotService;
import com.onelake.orchestration.service.RunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock private PipelineSnapshotService pipelineSnapshotService;
    @Mock private OrchestrationService orchestrationService;

    private PipelineSyncRefTriggerHandler handler;

    @BeforeEach
    void setup() {
        handler = new PipelineSyncRefTriggerHandler(dagRepo, pipelineSnapshotService, orchestrationService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void subscribesToIntegrationTableLoaded() {
        assertThat(handler.eventTypes())
                .isEqualTo(Set.of(DomainEvents.INTEGRATION_TABLE_LOADED));
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
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId));
        // 触发期间会设置租户上下文，触发后必须恢复为空。
        assertThat(TenantContext.getTenantId()).isNull();
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
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any());

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile"));
        verify(orchestrationService).triggerPipelineRun(
                eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId));
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
        assertThat(readinessState()).hasSize(1);

        stubPublishedPipeline(dag, List.of(user, profile, join), edges);
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.user_profile"));

        assertThat(readinessState()).hasSize(1);
        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any());
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
                any(), any(), any(RunContext.class), any());
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
                any(), any(), any(RunContext.class), any());
    }

    @Test
    void skipsPipelineInDraftStatus() {
        UUID tenantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        Dag dag = pipelineDag(tenantId, dagId, "VALIDATED", true);  // 无发布版本的生产事件必须跳过。
        when(dagRepo.findByTenantId(tenantId)).thenReturn(List.of(dag));

        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));

        verify(orchestrationService, never()).triggerPipelineRun(
                any(), any(), any(RunContext.class), any());
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
                any(), any(), any(RunContext.class), any());
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
                        eq(dagId), eq(TriggerType.EVENT), any(RunContext.class), eq(versionId));

        // 业务异常不应向外抛出，避免 Outbox 后台消费被单条事件打断。
        handler.handle(tableLoadedEvent(tenantId, "iceberg.ods.orders"));
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
        when(dagRepo.findByTenantId(dag.getTenantId())).thenReturn(List.of(dag));
        lenient().when(pipelineSnapshotService.loadExecutionSnapshot(versionId, dag.getId()))
                .thenReturn(new PipelineSnapshotService.ExecutionSnapshot(
                        version, dag, tasks, edges, List.of()));
        return versionId;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Map<String, Instant>> readinessState() {
        return (Map<Object, Map<String, Instant>>) ReflectionTestUtils.getField(
                handler, "readinessByVersion");
    }

    private OutboxEvent tableLoadedEvent(UUID tenantId, String targetTable) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        try {
            e.setPayload(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "tenantId", tenantId.toString(),
                    "targetTable", targetTable,
                    "runId", UUID.randomUUID().toString(),
                    "status", "SUCCEEDED"
            )));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        e.setOccurredAt(Instant.now());
        return e;
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

    private PipelineTaskEdge edge(UUID tenantId, UUID dagId, String sourceKey, String targetKey, String targetInput) {
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
        edge.setFreshnessPolicy("SAME_FRESHNESS_WINDOW");
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
