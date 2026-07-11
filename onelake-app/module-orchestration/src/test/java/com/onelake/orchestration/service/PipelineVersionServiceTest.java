package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionDiffDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineVersionServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PUBLISHED_VERSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PipelineSnapshotService snapshotService;
    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private PipelineParamRepository paramRepo;

    private PipelineVersionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new PipelineVersionService(snapshotService, dagRepo, taskRepo, edgeRepo, paramRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void diffsAddedRemovedAndFieldChangedTasksEdgesAndParams() throws Exception {
        String from = """
                {
                  "tasks": [
                    {"id":"old-tech-id","taskKey":"changed","name":"Old","config":{"sql":"select 1"}},
                    {"taskKey":"removed","name":"Removed"}
                  ],
                  "edges": [
                    {"sourceKey":"a","targetKey":"b","edgeLayer":"PIPELINE","triggerPolicy":"ALL_SUCCEEDED"},
                    {"sourceKey":"b","targetKey":"c","edgeLayer":"PIPELINE","triggerPolicy":"ALL_SUCCEEDED"}
                  ],
                  "pipeline_params": [
                    {"scope":"PIPELINE","taskKey":null,"paramKey":"changed","paramValue":"old","valueType":"STRING"},
                    {"scope":"TASK","taskKey":"changed","paramKey":"removed","paramValue":"1","valueType":"NUMBER"}
                  ]
                }
                """;
        String to = """
                {
                  "tasks": [
                    {"id":"new-tech-id","taskKey":"changed","name":"New","config":{"sql":"select 2"}},
                    {"taskKey":"added","name":"Added"}
                  ],
                  "edges": [
                    {"sourceKey":"a","targetKey":"b","edgeLayer":"PIPELINE","triggerPolicy":"ANY_SUCCEEDED"},
                    {"sourceKey":"c","targetKey":"d","edgeLayer":"PIPELINE","triggerPolicy":"ALL_SUCCEEDED"}
                  ],
                  "pipeline_params": [
                    {"scope":"PIPELINE","taskKey":null,"paramKey":"changed","paramValue":"new","valueType":"STRING"},
                    {"scope":"TASK","taskKey":"changed","paramKey":"added","paramValue":"2","valueType":"NUMBER"}
                  ]
                }
                """;
        when(snapshotService.getVersion(DAG_ID, 1)).thenReturn(detail(1, from));
        when(snapshotService.getVersion(DAG_ID, 2)).thenReturn(detail(2, to));

        PipelineVersionDiffDTO diff = service.diff(DAG_ID, 1, 2);

        assertThat(diff.tasks().added()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("added");
        assertThat(diff.tasks().removed()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("removed");
        assertThat(diff.tasks().changed()).singleElement().satisfies(item ->
                assertThat(item.fields()).extracting(PipelineVersionDiffDTO.FieldChange::field)
                        .containsExactly("config", "name"));
        assertThat(diff.edges().added()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("c->d[PIPELINE]");
        assertThat(diff.edges().removed()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("b->c[PIPELINE]");
        assertThat(diff.edges().changed()).singleElement().satisfies(item ->
                assertThat(item.fields()).extracting(PipelineVersionDiffDTO.FieldChange::field)
                        .containsExactly("triggerPolicy"));
        assertThat(diff.params().added()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("TASK:changed:added");
        assertThat(diff.params().removed()).extracting(PipelineVersionDiffDTO.ItemDiff::key)
                .containsExactly("TASK:changed:removed");
        assertThat(diff.params().changed()).singleElement().satisfies(item ->
                assertThat(item.fields()).extracting(PipelineVersionDiffDTO.FieldChange::field)
                        .containsExactly("paramValue"));
    }

    @Test
    void rollbackRestoresDraftAndLeavesHistoricalVersionImmutable() {
        Dag current = dag("current", "{\"canvas\":\"current\"}");
        current.setStatus("PUBLISHED");
        current.setVersion(9);
        current.setPublishedVersionId(PUBLISHED_VERSION_ID);
        current.setHasUnpublishedChanges(false);

        Dag targetDag = dag("target", "{\"canvas\":\"target\"}");
        targetDag.setScheduleCron("0 0 1 * * *");
        targetDag.setTimezone("UTC");
        PipelineTask targetTask = task("extract");
        PipelineTaskEdge targetEdge = edge("extract", "load");
        PipelineParam pipelineParam = param("PIPELINE", null, "region", "cn");
        PipelineParam taskParam = param("TASK", "extract", "limit", "100");
        PipelineParam globalParam = param("GLOBAL", null, "environment", "prod");
        UUID taskSnapshotId = targetTask.getId();
        UUID edgeSnapshotId = targetEdge.getId();
        UUID pipelineParamSnapshotId = pipelineParam.getId();
        UUID taskParamSnapshotId = taskParam.getId();
        PipelineTask savedTask = task("extract");
        PipelineTaskEdge savedEdge = edge("extract", "load");
        PipelineParam savedPipelineParam = param("PIPELINE", null, "region", "cn");
        PipelineParam savedTaskParam = param("TASK", "extract", "limit", "100");
        PipelineVersion historical = version(1, "immutable-snapshot");
        PipelineSnapshotService.ExecutionSnapshot target = new PipelineSnapshotService.ExecutionSnapshot(
                historical, targetDag, List.of(targetTask), List.of(targetEdge),
                List.of(globalParam, pipelineParam, taskParam));
        when(dagRepo.findByIdForUpdate(DAG_ID)).thenReturn(Optional.of(current));
        when(snapshotService.loadExecutionSnapshot(DAG_ID, 1)).thenReturn(target);
        when(taskRepo.saveAllAndFlush(anyList())).thenReturn(List.of(savedTask));
        when(edgeRepo.saveAllAndFlush(anyList())).thenReturn(List.of(savedEdge));
        when(paramRepo.saveAllAndFlush(anyList()))
                .thenReturn(List.of(savedPipelineParam, savedTaskParam));
        when(taskRepo.restoreSnapshotId(DAG_ID, savedTask.getId(), taskSnapshotId)).thenReturn(1);
        when(edgeRepo.restoreSnapshotId(DAG_ID, savedEdge.getId(), edgeSnapshotId)).thenReturn(1);
        when(paramRepo.restoreSnapshotId(
                TENANT_ID, DAG_ID, savedPipelineParam.getId(), pipelineParamSnapshotId)).thenReturn(1);
        when(paramRepo.restoreSnapshotId(
                TENANT_ID, DAG_ID, savedTaskParam.getId(), taskParamSnapshotId)).thenReturn(1);

        service.rollback(DAG_ID, 1);

        assertThat(current.getName()).isEqualTo("target");
        assertThat(current.getDefinition()).isEqualTo("{\"canvas\":\"target\"}");
        assertThat(current.getScheduleCron()).isEqualTo("0 0 1 * * *");
        assertThat(current.getTimezone()).isEqualTo("UTC");
        assertThat(current.getStatus()).isEqualTo("PUBLISHED");
        assertThat(current.getVersion()).isEqualTo(9);
        assertThat(current.getPublishedVersionId()).isEqualTo(PUBLISHED_VERSION_ID);
        assertThat(current.getHasUnpublishedChanges()).isTrue();
        verify(dagRepo).save(current);
        verify(edgeRepo).deleteDraftByDagId(DAG_ID);
        verify(taskRepo).deleteDraftByDagId(DAG_ID);
        verify(paramRepo).deleteDraftByTenantIdAndDagId(TENANT_ID, DAG_ID);
        verify(taskRepo).saveAllAndFlush(List.of(targetTask));
        verify(edgeRepo).saveAllAndFlush(List.of(targetEdge));
        verify(taskRepo).restoreSnapshotId(DAG_ID, savedTask.getId(), taskSnapshotId);
        verify(edgeRepo).restoreSnapshotId(DAG_ID, savedEdge.getId(), edgeSnapshotId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PipelineParam>> paramsCaptor = ArgumentCaptor.forClass(List.class);
        verify(paramRepo).saveAllAndFlush(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsExactly(pipelineParam, taskParam);
        verify(paramRepo).restoreSnapshotId(
                TENANT_ID, DAG_ID, savedPipelineParam.getId(), pipelineParamSnapshotId);
        verify(paramRepo).restoreSnapshotId(
                TENANT_ID, DAG_ID, savedTaskParam.getId(), taskParamSnapshotId);
        assertThat(historical.getVersion()).isEqualTo(1);
        assertThat(historical.getSnapshot()).isEqualTo("immutable-snapshot");
    }

    private PipelineVersionDetailDTO detail(int version, String snapshot) throws Exception {
        return new PipelineVersionDetailDTO(
                UUID.randomUUID(), DAG_ID, version, "checksum-" + version, "PUBLISHED",
                null, null, "publisher", Instant.parse("2026-07-11T00:00:00Z"),
                JsonUtil.mapper().readTree(snapshot));
    }

    private Dag dag(String name, String definition) {
        Dag dag = new Dag();
        dag.setId(DAG_ID);
        dag.setTenantId(TENANT_ID);
        dag.setName(name);
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition(definition);
        dag.setEnabled(true);
        dag.setPipelineKind("BLANK");
        dag.setEngine("SPARK");
        dag.setResourceGroup("spark-default");
        dag.setComputeProfile("spark-small");
        dag.setPartitionGrain("DAY");
        dag.setTimezone("Asia/Shanghai");
        dag.setScheduleMode("NORMAL");
        return dag;
    }

    private PipelineTask task(String taskKey) {
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(TENANT_ID);
        task.setDagId(DAG_ID);
        task.setTaskKey(taskKey);
        task.setTaskType(TaskType.SPARK_SQL);
        task.setName(taskKey);
        return task;
    }

    private PipelineTaskEdge edge(String sourceKey, String targetKey) {
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setId(UUID.randomUUID());
        edge.setTenantId(TENANT_ID);
        edge.setDagId(DAG_ID);
        edge.setSourceKey(sourceKey);
        edge.setTargetKey(targetKey);
        edge.setEdgeLayer(EdgeLayer.PIPELINE);
        return edge;
    }

    private PipelineParam param(String scope, String taskKey, String key, String value) {
        PipelineParam param = new PipelineParam();
        param.setId(UUID.randomUUID());
        param.setTenantId(TENANT_ID);
        param.setDagId("GLOBAL".equals(scope) ? null : DAG_ID);
        param.setScope(scope);
        param.setTaskKey(taskKey);
        param.setParamKey(key);
        param.setParamValue(value);
        return param;
    }

    private PipelineVersion version(int number, String snapshot) {
        PipelineVersion version = new PipelineVersion();
        version.setId(UUID.randomUUID());
        version.setTenantId(TENANT_ID);
        version.setDagId(DAG_ID);
        version.setVersion(number);
        version.setSnapshot(snapshot);
        version.setChecksum("checksum-" + number);
        return version;
    }
}
