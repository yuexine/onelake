package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.entity.TenantEntity;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.config.BuiltInOperatorCatalog;
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
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.never;
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
    @Mock private TenantRepository tenantRepo;
    @Mock private OperatorService operatorService;

    private PipelineService service;
    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        service = new PipelineService(dagRepo, taskRepo, edgeRepo, paramRepo, taskRunRepo,
                runRepo, compileService, operatorService, snapshotService, outboxProvider, tenantRepo);
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUsername("pipeline-author");
        lenient().when(tenantRepo.findByIdForUpdate(tenantId))
                .thenReturn(Optional.of(new TenantEntity()));
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
        lenient().when(snapshotService.publishSnapshot(any(), any())).thenAnswer(inv -> {
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
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
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
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());
        lenient().when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        Dag updated = service.updatePipelineStatus(dagId, "VALIDATED");

        assertThat(updated.getStatus()).isEqualTo("VALIDATED");
        assertThat(updated.getVersion()).isEqualTo(2); // 已自增。
        verify(tenantRepo, never()).findByIdForUpdate(tenantId);
    }

    @Test
    void publishedFromDraftRejected() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    void publishedEmitsOutboxEvent() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
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
    void approvalEnabledSubmitsSnapshotSummaryAndKeepsValidated() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        when(snapshotService.snapshot(dagId))
                .thenReturn(new PipelineSnapshotService.SnapshotPayload("{\"tasks\":[]}", "checksum-1"));
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "publishApprovalEnabled", true);

        Dag updated = service.updatePipelineStatus(dagId, "PUBLISHED");

        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        assertThat(updated.getStatus()).isEqualTo("VALIDATED");
        assertThat(updated.getVersion()).isEqualTo(1);
        verify(tenantRepo).findByIdForUpdate(tenantId);
        verify(outboxPublisher).publish(eq("pipeline.publish-approval.requested"),
                eq(dagId.toString()), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("requestType", "PUBLISH")
                .containsEntry("targetRef", dagId.toString())
                .containsEntry("applicantName", "pipeline-author")
                .containsEntry("snapshotChecksum", "checksum-1")
                .containsEntry("taskCount", 0);
        verify(snapshotService, never()).publishSnapshot(any());
    }

    @Test
    void publishApprovalConfigReflectsConfiguredSwitch() {
        assertThat(service.isPublishApprovalEnabled()).isFalse();

        ReflectionTestUtils.setField(service, "publishApprovalEnabled", true);

        assertThat(service.isPublishApprovalEnabled()).isTrue();
    }

    @Test
    void approvedDecisionPublishesMatchingSnapshotAndSetsPublished() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
        PipelineSnapshotService.SnapshotPayload verifiedSnapshot =
                new PipelineSnapshotService.SnapshotPayload("{}", "checksum-1");
        when(snapshotService.snapshot(dagId)).thenReturn(verifiedSnapshot);

        Dag updated = service.handlePublishApprovalDecision(dagId, "checksum-1", true, "approved");

        assertThat(updated.getStatus()).isEqualTo("PUBLISHED");
        assertThat(updated.getVersion()).isEqualTo(2);
        verify(snapshotService).publishSnapshot(dagId, verifiedSnapshot);
        verify(dagRepo).save(dag);
    }

    @Test
    void publishRevalidatesTenantRuntimeCapability() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(invalidCompileResult());

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("租户执行能力校验未通过");

        verify(snapshotService, never()).publishSnapshot(any());
    }

    @Test
    void rejectedDecisionKeepsValidatedWithoutPublishing() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));

        Dag updated = service.handlePublishApprovalDecision(dagId, "checksum-1", false, "risk rejected");

        assertThat(updated.getStatus()).isEqualTo("VALIDATED");
        assertThat(updated.getVersion()).isEqualTo(1);
        verify(snapshotService, never()).publishSnapshot(any());
        verify(dagRepo, never()).save(any(Dag.class));
    }

    @Test
    void sameStatusIsNoOp() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));

        Dag updated = service.updatePipelineStatus(dagId, "DRAFT");

        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getVersion()).isEqualTo(1); // 未变化。
    }

    @Test
    void publishedWithoutVersionCanBeRepublishedAfterMigration() {
        Dag dag = dag("PUBLISHED");
        dag.setPublishedVersionId(null);
        dag.setHasUnpublishedChanges(false);
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());

        service.updatePipelineStatus(dagId, "PUBLISHED");

        verify(snapshotService).publishSnapshot(dagId);
    }

    @Test
    void invalidStatusRejected() {
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
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
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
    void createsTrinoExtensionTaskWithServerOwnedCategoryAndEngine() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "trino_orders")).thenReturn(Optional.empty());
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> {
            PipelineTask task = inv.getArgument(0);
            task.setId(UUID.randomUUID());
            return task;
        });

        var created = service.createTask(dagId, new PipelineTaskRequest(
                "trino_orders", "TRINO_SQL", "Trino orders", null, null,
                null, null, java.util.Map.of("sql", "SELECT 1"), null, null));

        assertThat(created.taskType())
                .isEqualTo(com.onelake.orchestration.domain.enums.TaskType.TRINO_SQL);
        assertThat(created.category())
                .isEqualTo(com.onelake.orchestration.domain.enums.TaskCategory.EXEC);
        assertThat(created.engine()).isEqualTo("TRINO");
        verify(dagRepo).markPublishedDagChanged(dagId, tenantId);
    }

    @Test
    void createsOperatorTaskWithLockedReferenceAndVersion() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "select_fields")).thenReturn(Optional.empty());
        when(operatorService.getInstalledManifest(tenantId, "transform.select_columns", "1.0.0"))
                .thenReturn(BuiltInOperatorCatalog.manifests().stream()
                        .filter(manifest -> manifest.operatorRef().equals("transform.select_columns"))
                        .findFirst().orElseThrow());
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> {
            PipelineTask task = inv.getArgument(0);
            task.setId(UUID.randomUUID());
            return task;
        });

        var created = service.createTask(dagId, new PipelineTaskRequest(
                "select_fields", "SPARK_SQL", "选择字段", "SPARK_SQL",
                "onelake.dwd.selected_orders", null, null,
                "transform.select_columns", "1.0.0",
                java.util.Map.of("columns", List.of("order_id")), null, null));

        assertThat(created.operatorRef()).isEqualTo("transform.select_columns");
        assertThat(created.operatorVersion()).isEqualTo("1.0.0");
    }

    @Test
    void rejectsOperatorTaskWithoutLockedVersion() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.createTask(dagId, new PipelineTaskRequest(
                "select_fields", "SPARK_SQL", "选择字段", "SPARK_SQL",
                "onelake.dwd.selected_orders", null, null,
                "transform.select_columns", null,
                java.util.Map.of("columns", List.of("order_id")), null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须成对提交");
    }

    @Test
    void rejectsOperatorTemplateOutsideG1AtTaskCreation() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        var unsupported = BuiltInOperatorCatalog.manifests().stream()
                .filter(manifest -> manifest.operatorRef().equals("join.inner"))
                .findFirst().orElseThrow();
        when(operatorService.getInstalledManifest(
                tenantId, unsupported.operatorRef(), unsupported.version())).thenReturn(unsupported);

        assertThatThrownBy(() -> service.createTask(dagId, new PipelineTaskRequest(
                "join_orders", "SPARK_SQL", "关联", "SPARK_SQL",
                "onelake.dwd.joined_orders", null, null,
                unsupported.operatorRef(), unsupported.version(),
                java.util.Map.of("on", "left.id = right.id"), null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在 G1 Spark SQL 支持范围");
    }

    @Test
    void rejectsUninstalledOperatorBindingThroughGenericTaskCreation() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, "custom.not_installed", "1.0.0"))
                .thenThrow(new BizException(40402,
                        "算子未安装、不可见或已废弃: custom.not_installed"));

        assertThatThrownBy(() -> service.createTask(dagId, new PipelineTaskRequest(
                "not_installed", "SPARK_SQL", "未安装算子", "SPARK_SQL",
                "onelake.tmp.not_installed", null, null,
                "custom.not_installed", "1.0.0",
                java.util.Map.of(), null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未安装");

        verify(taskRepo, never()).save(any(PipelineTask.class));
    }

    @Test
    void existingLockedBindingCanBeUpdatedAfterOperatorDeprecation() {
        Dag dag = dag("DRAFT");
        PipelineTask task = operatorTask("transform.select_columns", "1.0.0");
        var manifest = BuiltInOperatorCatalog.manifests().stream()
                .filter(item -> item.operatorRef().equals("transform.select_columns"))
                .findFirst().orElseThrow();
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "select_fields")).thenReturn(Optional.of(task));
        when(operatorService.getManifest(tenantId, task.getOperatorRef(), task.getOperatorVersion()))
                .thenReturn(manifest);
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateTask(dagId, "select_fields", new PipelineTaskRequest(
                null, null, "已废弃算子的既有节点", null, null, null, null,
                task.getOperatorRef(), task.getOperatorVersion(),
                java.util.Map.of("columns", List.of("order_id")), 100, 200));

        assertThat(updated.name()).isEqualTo("已废弃算子的既有节点");
        verify(operatorService).getManifest(tenantId, task.getOperatorRef(), task.getOperatorVersion());
        verify(operatorService, never()).getInstalledManifest(any(), anyString(), anyString());
    }

    @Test
    void changingLockedBindingStillRequiresActiveInstalledOperator() {
        Dag dag = dag("DRAFT");
        PipelineTask task = operatorTask("transform.select_columns", "1.0.0");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "select_fields")).thenReturn(Optional.of(task));
        when(operatorService.getInstalledManifest(tenantId, "custom.deprecated", "1.0.0"))
                .thenThrow(new BizException(40402,
                        "算子未安装、不可见或已废弃: custom.deprecated"));

        assertThatThrownBy(() -> service.updateTask(dagId, "select_fields", new PipelineTaskRequest(
                null, null, "改绑", null, null, null, null,
                "custom.deprecated", "1.0.0", java.util.Map.of(), null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已废弃");

        verify(taskRepo, never()).save(any(PipelineTask.class));
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
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "transform")).thenReturn(Optional.of(task));
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateTask(dagId, "transform", new PipelineTaskRequest(
                null, null, "changed", null, null, null, null,
                java.util.Map.of("sql", "SELECT 2"), null, null));

        verify(dagRepo).markPublishedDagChanged(dagId, tenantId);
    }

    @Test
    void editingValidatedTaskStillChecksDatabaseStatusForConcurrentApproval() {
        Dag dag = dag("VALIDATED");
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(dagId);
        task.setTaskKey("transform");
        task.setTaskType(com.onelake.orchestration.domain.enums.TaskType.SPARK_SQL);
        task.setName("transform");
        task.setEngine("SPARK_SQL");
        task.setConfig("{\"sql\":\"SELECT 1\"}");
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
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

    private PipelineTask operatorTask(String operatorRef, String operatorVersion) {
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(dagId);
        task.setTaskKey("select_fields");
        task.setTaskType(com.onelake.orchestration.domain.enums.TaskType.SPARK_SQL);
        task.setName("选择字段");
        task.setEngine("SPARK_SQL");
        task.setOperatorRef(operatorRef);
        task.setOperatorVersion(operatorVersion);
        task.setConfig("{\"columns\":[\"order_id\"]}");
        return task;
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
