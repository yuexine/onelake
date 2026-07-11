package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.dto.PipelineValidationResult;
import com.onelake.orchestration.dto.PipelineTaskRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineService#updatePipelineStatus} 的 P4-D 状态机测试。
 *
 * <p>验证 DRAFT→VALIDATED→PUBLISHED 流转，以及进入 PUBLISHED 时的 Outbox 事件。
 */
@ExtendWith(MockitoExtension.class)
class PipelineStatusMachineTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private PipelineParamRepository paramRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private JobRunRepository runRepo;
    @Mock private PipelineCompileService compileService;
    @Mock private PipelineSnapshotService snapshotService;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private OutboxPublisher outboxPublisher;

    private PipelineService service;
    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        service = new PipelineService(dagRepo, taskRepo, edgeRepo, paramRepo, taskRunRepo,
                runRepo, compileService, snapshotService, outboxProvider);
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(UUID.randomUUID());
        lenient().doAnswer(inv -> {
            Dag d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        }).when(dagRepo).save(any(Dag.class));
        lenient().when(snapshotService.publishSnapshot(any())).thenAnswer(inv -> {
            PipelineVersion version = new PipelineVersion();
            version.setId(UUID.randomUUID());
            return version;
        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void validatedTransitionRequiresValidationPass() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        // 编译返回无效计划，validate() 也应返回无效结果。
        lenient().when(compileService.compile(dagId)).thenReturn(invalidCompileResult());
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "VALIDATED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("校验未通过");
    }

    @Test
    void validatedTransitionSucceedsWhenCompilePasses() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());
        lenient().when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        Dag updated = service.updatePipelineStatus(dagId, "VALIDATED");

        assertThat(updated.getStatus()).isEqualTo("VALIDATED");
        assertThat(updated.getVersion()).isEqualTo(2); // 已自增。
    }

    @Test
    void publishedFromDraftRejected() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    void publishedEmitsOutboxEvent() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());

        service.updatePipelineStatus(dagId, "PUBLISHED");

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(outboxPublisher).publish(typeCaptor.capture(), anyString(), payloadCaptor.capture());
        assertThat(typeCaptor.getValue()).isEqualTo("pipeline.published");
        assertThat(payloadCaptor.getValue().get("pipelineId")).isEqualTo(dagId.toString());
        assertThat(payloadCaptor.getValue().get("version")).isEqualTo(2);
        assertThat(payloadCaptor.getValue().get("versionId")).isNotNull();
    }

    @Test
    void sameStatusIsNoOp() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        Dag updated = service.updatePipelineStatus(dagId, "DRAFT");

        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getVersion()).isEqualTo(1); // 未变化。
    }

    @Test
    void publishedWithoutVersionCanBeRepublishedAfterMigration() {
        Dag dag = dag("PUBLISHED");
        dag.setPublishedVersionId(null);
        dag.setHasUnpublishedChanges(false);
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());

        service.updatePipelineStatus(dagId, "PUBLISHED");

        verify(snapshotService).publishSnapshot(dagId);
    }

    @Test
    void invalidStatusRejected() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "ARCHIVED"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void listTaskRunsRejectsRunOutsideCurrentPipeline() {
        Dag dag = dag("PUBLISHED");
        UUID otherRunId = UUID.randomUUID();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(otherRunId, Set.of(dagId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listTaskRuns(dagId, otherRunId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("运行实例不存在");

        verifyNoInteractions(taskRunRepo);
    }

    @Test
    void deletingTaskAlsoDeletesItsScopedParams() {
        Dag dag = dag("DRAFT");
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(dagId);
        task.setTaskKey("transform");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKeyForUpdate(dagId, "transform"))
                .thenReturn(Optional.of(task));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        service.deleteTask(dagId, "transform");

        verify(paramRepo).deleteByTenantIdAndDagIdAndTaskKeyAndScope(
                tenantId, dagId, "transform", "TASK");
        verify(taskRepo).findByDagIdAndTaskKeyForUpdate(dagId, "transform");
        verify(taskRepo).delete(task);
    }

    @Test
    void editingPublishedTaskAlwaysSerializesWithPublishEvenWhenAlreadyMarked() {
        Dag dag = dag("PUBLISHED");
        dag.setHasUnpublishedChanges(true);
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(dagId);
        task.setTaskKey("transform");
        task.setTaskType(com.onelake.orchestration.domain.enums.TaskType.SPARK_SQL);
        task.setName("transform");
        task.setEngine("SPARK_SQL");
        task.setConfig("{\"sql\":\"SELECT 1\"}");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "transform")).thenReturn(Optional.of(task));
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateTask(dagId, "transform", new PipelineTaskRequest(
                null, null, "changed", null, null, null, null,
                java.util.Map.of("sql", "SELECT 2"), null, null));

        verify(dagRepo).markPublishedDagChanged(dagId, tenantId);
    }

    private Dag dag(String status) {
        Dag d = new Dag();
        d.setId(dagId);
        d.setTenantId(tenantId);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setEnabled(true);
        d.setVersion(1);
        d.setStatus(status);
        d.setPipelineKind("BLANK");
        d.setEngine("SPARK");
        return d;
    }

    private com.onelake.orchestration.dto.PipelineCompileResult validCompileResult() {
        return new com.onelake.orchestration.dto.PipelineCompileResult(
                dagId, "pipeline_" + dagId, tenantId, List.of(), true, List.of());
    }

    private com.onelake.orchestration.dto.PipelineCompileResult invalidCompileResult() {
        return new com.onelake.orchestration.dto.PipelineCompileResult(
                dagId, "pipeline_" + dagId, tenantId, List.of(), false, List.of("cycle"));
    }
}
