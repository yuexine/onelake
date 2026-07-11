package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.PipelineVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineSnapshotServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private PipelineParamRepository paramRepo;
    @Mock private PipelineVersionRepository versionRepo;

    private PipelineSnapshotService service;
    private Dag dag;
    private PipelineTask task;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUsername("publisher");
        service = new PipelineSnapshotService(dagRepo, taskRepo, edgeRepo, paramRepo, versionRepo);
        dag = dag();
        task = task("transform", "{\"z\":1,\"sql\":\"SELECT 1\",\"a\":2}");
        org.mockito.Mockito.lenient().when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID))
                .thenReturn(Optional.of(dag));
        org.mockito.Mockito.lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenAnswer(inv -> List.of(task));
        org.mockito.Mockito.lenient().when(edgeRepo.findByDagId(DAG_ID)).thenReturn(List.of(edge()));
        org.mockito.Mockito.lenient().when(paramRepo.findByTenantIdAndDagIdAndScope(TENANT_ID, DAG_ID, "PIPELINE"))
                .thenReturn(List.of(param("PIPELINE", null, "region", "cn")));
        org.mockito.Mockito.lenient().when(paramRepo.findByTenantIdAndDagIdAndScope(TENANT_ID, DAG_ID, "TASK"))
                .thenReturn(List.of(param("TASK", "transform", "limit", "100")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void generatesCanonicalCompleteSnapshotAndStableChecksum() throws Exception {
        PipelineTaskEdge pipelineEdge = edge();
        PipelineTaskEdge crossEngineEdge = edge();
        crossEngineEdge.setEdgeLayer(EdgeLayer.CROSS_ENGINE);
        when(edgeRepo.findByDagId(DAG_ID))
                .thenReturn(List.of(pipelineEdge, crossEngineEdge),
                        List.of(crossEngineEdge, pipelineEdge));
        PipelineSnapshotService.SnapshotPayload first = service.snapshot(DAG_ID);
        task.setConfig("{\"a\":2,\"sql\":\"SELECT 1\",\"z\":1}");
        dag.setVersion(99); // 状态机修订号不属于可执行内容，不应破坏发布幂等。
        PipelineSnapshotService.SnapshotPayload second = service.snapshot(DAG_ID);

        assertThat(second.checksum()).isEqualTo(first.checksum()).hasSize(64);
        JsonNode root = JsonUtil.mapper().readTree(first.json());
        assertThat(root.has("dag")).isTrue();
        assertThat(root.path("tasks").get(0).path("taskKey").asText()).isEqualTo("transform");
        assertThat(root.path("edges")).hasSize(2);
        assertThat(root.path("edges").get(0).path("edgeLayer").asText()).isEqualTo("CROSS_ENGINE");
        assertThat(root.path("pipeline_params")).hasSize(2);
        assertThat(root.path("schedule").path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(first.json()).contains("\"config\":{\"a\":2,\"sql\":\"SELECT 1\",\"z\":1}");
    }

    @Test
    void publishIsIdempotentAndReusesChecksumVersion() {
        AtomicReference<PipelineVersion> stored = new AtomicReference<>();
        when(dagRepo.findByIdForUpdate(DAG_ID)).thenReturn(Optional.of(dag));
        when(versionRepo.findFirstByDagIdAndChecksumOrderByVersionDesc(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        PipelineVersion previous = new PipelineVersion();
        previous.setVersion(3);
        when(versionRepo.findByDagIdOrderByVersionDesc(DAG_ID)).thenReturn(List.of(previous));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> {
            PipelineVersion version = inv.getArgument(0);
            version.setId(UUID.randomUUID());
            stored.set(version);
            return version;
        });

        PipelineVersion first = service.publishSnapshot(DAG_ID);
        PipelineVersion second = service.publishSnapshot(DAG_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getVersion()).isEqualTo(4);
        assertThat(dag.getPublishedVersionId()).isEqualTo(first.getId());
        assertThat(dag.getHasUnpublishedChanges()).isFalse();
        verify(versionRepo, times(1)).save(any(PipelineVersion.class));
    }

    @Test
    void publishingRolledBackHistoricalContentCreatesNextLinearVersion() {
        PipelineSnapshotService.SnapshotPayload payload = service.snapshot(DAG_ID);
        PipelineVersion historicalMatch = version(1, payload.json());
        historicalMatch.setChecksum(payload.checksum());
        PipelineVersion currentPublished = version(2, "{\"different\":true}");
        dag.setPublishedVersionId(currentPublished.getId());
        when(dagRepo.findByIdForUpdate(DAG_ID)).thenReturn(Optional.of(dag));
        when(versionRepo.findFirstByDagIdAndChecksumOrderByVersionDesc(DAG_ID, payload.checksum()))
                .thenReturn(Optional.of(historicalMatch));
        when(versionRepo.findByDagIdOrderByVersionDesc(DAG_ID))
                .thenReturn(List.of(currentPublished, historicalMatch));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(invocation -> {
            PipelineVersion saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        PipelineVersion republished = service.publishSnapshot(DAG_ID);

        assertThat(republished.getVersion()).isEqualTo(3);
        assertThat(republished.getId()).isNotEqualTo(historicalMatch.getId());
        assertThat(dag.getPublishedVersionId()).isEqualTo(republished.getId());
        verify(versionRepo).save(any(PipelineVersion.class));
    }

    @Test
    void editingDraftAfterPublishDoesNotChangeStoredExecutionSnapshot() {
        AtomicReference<PipelineVersion> stored = new AtomicReference<>();
        when(dagRepo.findByIdForUpdate(DAG_ID)).thenReturn(Optional.of(dag));
        when(versionRepo.findFirstByDagIdAndChecksumOrderByVersionDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(versionRepo.findByDagIdOrderByVersionDesc(DAG_ID)).thenReturn(List.of());
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> {
            PipelineVersion version = inv.getArgument(0);
            version.setId(UUID.randomUUID());
            stored.set(version);
            return version;
        });

        PipelineVersion published = service.publishSnapshot(DAG_ID);
        task.setConfig("{\"sql\":\"SELECT 999\"}");
        when(versionRepo.findById(published.getId())).thenAnswer(inv -> Optional.of(stored.get()));

        PipelineSnapshotService.ExecutionSnapshot execution =
                service.loadExecutionSnapshot(published.getId(), DAG_ID);

        assertThat(execution.tasks()).singleElement()
                .satisfies(snapshotTask -> assertThat(snapshotTask.getConfig()).contains("SELECT 1"));
        assertThat(execution.params()).extracting(PipelineParam::getParamKey)
                .containsExactly("region", "limit");
    }

    @Test
    void activatesOnlyRequestedVersionWithExpiringDatabaseLease() {
        UUID versionId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setId(versionId);
        version.setTenantId(TENANT_ID);
        when(versionRepo.findById(versionId)).thenReturn(Optional.of(version));
        Instant before = Instant.now();

        service.activateForDagster(versionId);

        verify(versionRepo).activateDagsterGraphVersion(
                eq(versionId),
                org.mockito.ArgumentMatchers.argThat(expiresAt ->
                        expiresAt.isAfter(before.plusSeconds(4 * 60))
                                && expiresAt.isBefore(before.plusSeconds(6 * 60))));
    }

    @Test
    void dagsterDefinitionsUseCurrentAndActiveVersionsInsteadOfAllHistory() {
        PipelineSnapshotService.SnapshotPayload payload = service.snapshot(DAG_ID);
        PipelineVersion selected = new PipelineVersion();
        selected.setId(UUID.randomUUID());
        selected.setTenantId(TENANT_ID);
        selected.setDagId(DAG_ID);
        selected.setVersion(7);
        selected.setSnapshot(payload.json());
        when(versionRepo.findDagsterGraphDefinitionVersions(any(Instant.class)))
                .thenReturn(List.of(selected));

        List<PipelineSnapshotService.ExecutionSnapshot> snapshots =
                service.listExecutionSnapshotsForDagster();

        assertThat(snapshots).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.version().getId()).isEqualTo(selected.getId()));
        verify(versionRepo).deleteExpiredDagsterGraphActivations(any(Instant.class));
        verify(versionRepo, never()).findAll();
    }

    @Test
    void listsVersionHistoryAndReturnsCompleteSnapshotDetail() {
        PipelineSnapshotService.SnapshotPayload payload = service.snapshot(DAG_ID);
        PipelineVersion version = version(2, payload.json());
        when(versionRepo.findByDagIdOrderByVersionDesc(DAG_ID)).thenReturn(List.of(version));
        when(versionRepo.findByDagIdAndVersion(DAG_ID, 2)).thenReturn(Optional.of(version));

        assertThat(service.listVersions(DAG_ID)).singleElement().satisfies(summary -> {
            assertThat(summary.version()).isEqualTo(2);
            assertThat(summary.checksum()).isEqualTo("checksum-2");
        });
        assertThat(service.getVersion(DAG_ID, 2).snapshot().path("tasks"))
                .hasSize(1);
        assertThat(service.getVersion(DAG_ID, 2).snapshot().path("pipeline_params"))
                .hasSize(2);
    }

    @Test
    void resolvesRunVersionNumbersInOneBatchWithinTenant() {
        PipelineVersion visible = version(3, "{}");
        PipelineVersion foreign = version(9, "{}");
        foreign.setId(UUID.randomUUID());
        foreign.setTenantId(UUID.randomUUID());
        when(versionRepo.findAllById(Set.of(visible.getId(), foreign.getId())))
                .thenReturn(List.of(visible, foreign));

        assertThat(service.versionNumbers(Set.of(visible.getId(), foreign.getId())))
                .containsExactlyEntriesOf(java.util.Map.of(visible.getId(), 3));
    }

    private PipelineVersion version(int number, String snapshot) {
        PipelineVersion version = new PipelineVersion();
        version.setId(UUID.randomUUID());
        version.setTenantId(TENANT_ID);
        version.setDagId(DAG_ID);
        version.setVersion(number);
        version.setSnapshot(snapshot);
        version.setChecksum("checksum-" + number);
        version.setStatus("PUBLISHED");
        version.setPublishedByName("publisher");
        version.setCreatedAt(Instant.parse("2026-07-11T00:00:00Z"));
        return version;
    }

    private Dag dag() {
        Dag value = new Dag();
        value.setId(DAG_ID);
        value.setTenantId(TENANT_ID);
        value.setName("orders");
        value.setDagsterJob("onelake_pipeline_run");
        value.setDefinition("{\"zoom\":1}");
        value.setStatus("PUBLISHED");
        value.setPipelineKind("BLANK");
        value.setEngine("SPARK");
        value.setResourceGroup("spark-default");
        value.setComputeProfile("spark-small");
        value.setPartitionGrain("DAY");
        value.setScheduleCron("0 0 2 * * *");
        value.setTimezone("Asia/Shanghai");
        value.setScheduleMode("NORMAL");
        value.setHasUnpublishedChanges(true);
        return value;
    }

    private PipelineTask task(String key, String config) {
        PipelineTask value = new PipelineTask();
        value.setId(UUID.randomUUID());
        value.setTenantId(TENANT_ID);
        value.setDagId(DAG_ID);
        value.setTaskKey(key);
        value.setTaskType(TaskType.SPARK_SQL);
        value.setName(key);
        value.setEngine("SPARK_SQL");
        value.setTargetFqn("onelake.dwd.orders");
        value.setConfig(config);
        return value;
    }

    private PipelineTaskEdge edge() {
        PipelineTaskEdge value = new PipelineTaskEdge();
        value.setId(UUID.randomUUID());
        value.setTenantId(TENANT_ID);
        value.setDagId(DAG_ID);
        value.setSourceKey("source");
        value.setTargetKey("transform");
        value.setEdgeLayer(EdgeLayer.PIPELINE);
        return value;
    }

    private PipelineParam param(String scope, String taskKey, String key, String value) {
        PipelineParam param = new PipelineParam();
        param.setId(UUID.randomUUID());
        param.setTenantId(TENANT_ID);
        param.setDagId(DAG_ID);
        param.setScope(scope);
        param.setTaskKey(taskKey);
        param.setParamKey(key);
        param.setParamValue(value);
        param.setValueType("STRING");
        return param;
    }
}
