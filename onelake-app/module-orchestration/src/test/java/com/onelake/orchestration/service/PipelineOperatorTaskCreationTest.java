package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.config.BuiltInOperatorCatalog;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskCategory;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import com.onelake.orchestration.dto.OperatorTaskCreateRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineOperatorTaskCreationTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private PipelineParamRepository paramRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private JobRunRepository runRepo;
    @Mock private OperatorService operatorService;
    @Mock private PipelineSnapshotService snapshotService;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private TenantRepository tenantRepo;

    private PipelineService pipelineService;
    private UUID tenantId;
    private UUID dagId;
    private Dag dag;

    @BeforeEach
    void setUp() {
        PipelineCompileService compileService = new PipelineCompileService(
            dagRepo, taskRepo, edgeRepo, new ScriptSandboxPolicy(true, "*"),
            operatorService, new OperatorSqlGenerator());
        pipelineService = new PipelineService(
            dagRepo, taskRepo, edgeRepo, paramRepo, taskRunRepo, runRepo,
            compileService, operatorService, snapshotService, outboxProvider, tenantRepo);
        tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        dagId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TenantContext.setTenantId(tenantId);
        dag = new Dag();
        dag.setId(dagId);
        dag.setTenantId(tenantId);
        dag.setName("operator_pipeline");
        dag.setStatus("DRAFT");
        dag.setEngine("SPARK");
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsTaskFromManifestDefaultsAndCompilesThroughG1() {
        OperatorManifestDTO manifest = inputManifestWithSchemaDefault();
        AtomicReference<PipelineTask> saved = new AtomicReference<>();
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, manifest.operatorRef(), manifest.version()))
            .thenReturn(manifest);
        when(operatorService.getManifest(tenantId, manifest.operatorRef(), manifest.version()))
            .thenReturn(manifest);
        when(taskRepo.findByDagIdAndTaskKey(dagId, "input_ods_table")).thenReturn(Optional.empty());
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(invocation -> {
            PipelineTask task = invocation.getArgument(0);
            task.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
            saved.set(task);
            return task;
        });
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId))
            .thenAnswer(ignored -> List.of(saved.get()));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        var created = pipelineService.createTaskFromOperator(
            dagId, manifest.operatorRef(), manifest.version(),
            new OperatorTaskCreateRequest.Position(320, 240));

        assertThat(created.taskKey()).isEqualTo("input_ods_table");
        assertThat(created.taskType()).isEqualTo(TaskType.SPARK_SQL);
        assertThat(created.category()).isEqualTo(TaskCategory.EXEC);
        assertThat(created.engine()).isEqualTo("SPARK_SQL");
        assertThat(created.name()).isEqualTo("ODS 表输入");
        assertThat(created.operatorRef()).isEqualTo("input.ods_table");
        assertThat(created.operatorVersion()).isEqualTo("1.0.0");
        assertThat(created.positionX()).isEqualTo(320);
        assertThat(created.positionY()).isEqualTo(240);
        assertThat(created.targetFqn()).isEqualTo("onelake.tmp.input_ods_table");
        assertThat(created.config()).containsEntry("sourceFqn", "ods.schema_default_orders");
        assertThat(created.config()).containsEntry("fallbackLabel", "from-example");
        assertThat(created.config()).extractingByKey("_operator_contract")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("category", "INPUT")
            .containsEntry("inputPorts", List.of())
            .containsEntry("outputSchema", manifest.outputSchema());

        var validation = pipelineService.validate(dagId);

        assertThat(validation.valid()).isTrue();
        assertThat(saved.get().getExecutable()).isTrue();
        assertThat(saved.get().getConfig())
            .contains("CREATE OR REPLACE TABLE `onelake`.`tmp`.`input_ods_table` AS");
        assertThat(saved.get().getConfig()).contains("`ods`.`schema_default_orders`");
        verify(dagRepo).markPublishedDagChanged(dagId, tenantId);
    }

    @Test
    void appendsStableSuffixWhenOperatorTaskKeyAlreadyExists() {
        OperatorManifestDTO manifest = inputManifestWithSchemaDefault();
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, manifest.operatorRef(), manifest.version()))
            .thenReturn(manifest);
        when(taskRepo.findByDagIdAndTaskKey(dagId, "input_ods_table"))
            .thenReturn(Optional.of(new PipelineTask()));
        when(taskRepo.findByDagIdAndTaskKey(dagId, "input_ods_table_2"))
            .thenReturn(Optional.empty());
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = pipelineService.createTaskFromOperator(
            dagId, manifest.operatorRef(), manifest.version(),
            new OperatorTaskCreateRequest.Position(10, 20));

        assertThat(created.taskKey()).isEqualTo("input_ods_table_2");
        assertThat(created.targetFqn()).isEqualTo("onelake.tmp.input_ods_table_2");
    }

    @Test
    void rejectsUninstalledOperatorBeforeSavingTask() {
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, "custom.not_installed", "1.0.0"))
            .thenThrow(new BizException(40402, "算子未安装、不可见或已废弃: custom.not_installed"));

        assertThatThrownBy(() -> pipelineService.createTaskFromOperator(
            dagId, "custom.not_installed", "1.0.0",
            new OperatorTaskCreateRequest.Position(10, 20)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未安装");

        verify(taskRepo, never()).save(any());
    }

    @Test
    void rejectsG1ManifestWithMultipleInputPortsBeforeSavingTask() {
        OperatorManifestDTO base = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("transform.select_columns"))
            .findFirst().orElseThrow();
        OperatorManifestDTO multipleInputs = withInputPorts(base, List.of(
            Map.of("name", "left", "cardinality", "ONE"),
            Map.of("name", "right", "cardinality", "ONE")));
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, base.operatorRef(), base.version()))
            .thenReturn(multipleInputs);

        assertThatThrownBy(() -> pipelineService.createTaskFromOperator(
            dagId, base.operatorRef(), base.version(),
            new OperatorTaskCreateRequest.Position(10, 20)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不在 G1 Spark SQL 支持范围");

        verify(taskRepo, never()).save(any());
    }

    @Test
    void rejectsSourceManifestWhoseTemplateRequiresUpstreamInput() {
        OperatorManifestDTO base = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("govern.trim_whitespace"))
            .findFirst().orElseThrow();
        OperatorManifestDTO invalidSource = withInputPorts(base, List.of());
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, base.operatorRef(), base.version()))
            .thenReturn(invalidSource);

        assertThatThrownBy(() -> pipelineService.createTaskFromOperator(
            dagId, base.operatorRef(), base.version(),
            new OperatorTaskCreateRequest.Position(10, 20)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不在 G1 Spark SQL 支持范围");

        verify(taskRepo, never()).save(any());
    }

    @Test
    void rejectsRequestedVersionThatConflictsWithPinnedInstallation() {
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(tenantId, "mask.partial", "2.0.0"))
            .thenThrow(new BizException(40912, "算子已固定版本 1.0.0，不能创建版本 2.0.0"));

        assertThatThrownBy(() -> pipelineService.createTaskFromOperator(
            dagId, "mask.partial", "2.0.0",
            new OperatorTaskCreateRequest.Position(10, 20)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已固定版本");

        verify(taskRepo, never()).save(any());
    }

    @Test
    void historicalNullExampleFallsBackToSchemaDefaultsWithoutServerError() {
        OperatorManifestDTO base = inputManifestWithSchemaDefault();
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(null);
        OperatorManifestDTO historical = withExamples(base, examples);
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(operatorService.getInstalledManifest(
            tenantId, historical.operatorRef(), historical.version())).thenReturn(historical);
        when(taskRepo.findByDagIdAndTaskKey(dagId, "input_ods_table")).thenReturn(Optional.empty());
        when(taskRepo.save(any(PipelineTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = pipelineService.createTaskFromOperator(
            dagId, historical.operatorRef(), historical.version(),
            new OperatorTaskCreateRequest.Position(10, 20));

        assertThat(created.config()).containsEntry("sourceFqn", "ods.schema_default_orders")
            .doesNotContainKey("fallbackLabel");
    }

    private OperatorManifestDTO inputManifestWithSchemaDefault() {
        OperatorManifestDTO builtIn = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("input.ods_table"))
            .findFirst().orElseThrow();
        Map<String, Object> schema = new LinkedHashMap<>(builtIn.paramsSchema());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceFqn", Map.of("type", "string", "default", "ods.schema_default_orders"));
        properties.put("fallbackLabel", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("sourceFqn"));
        return new OperatorManifestDTO(
            builtIn.operatorRef(), builtIn.version(), builtIn.category(), builtIn.scope(),
            builtIn.displayName(), builtIn.description(), builtIn.icon(), builtIn.tags(),
            builtIn.inputPorts(), builtIn.outputSchema(), schema, builtIn.compileTarget(),
            builtIn.template(), builtIn.lineageRule(), builtIn.securityRule(), builtIn.qualityEmit(),
            builtIn.policy(), builtIn.resourceHint(),
            List.of(Map.of("params", Map.of(
                "sourceFqn", "ods.example_orders",
                "fallbackLabel", "from-example"))));
    }

    private OperatorManifestDTO withInputPorts(OperatorManifestDTO manifest,
                                               List<Map<String, Object>> inputPorts) {
        return new OperatorManifestDTO(
            manifest.operatorRef(), manifest.version(), manifest.category(), manifest.scope(),
            manifest.displayName(), manifest.description(), manifest.icon(), manifest.tags(),
            inputPorts, manifest.outputSchema(), manifest.paramsSchema(), manifest.compileTarget(),
            manifest.template(), manifest.lineageRule(), manifest.securityRule(), manifest.qualityEmit(),
            manifest.policy(), manifest.resourceHint(), manifest.examples());
    }

    private OperatorManifestDTO withExamples(OperatorManifestDTO manifest,
                                              List<Map<String, Object>> examples) {
        return new OperatorManifestDTO(
            manifest.operatorRef(), manifest.version(), manifest.category(), manifest.scope(),
            manifest.displayName(), manifest.description(), manifest.icon(), manifest.tags(),
            manifest.inputPorts(), manifest.outputSchema(), manifest.paramsSchema(), manifest.compileTarget(),
            manifest.template(), manifest.lineageRule(), manifest.securityRule(), manifest.qualityEmit(),
            manifest.policy(), manifest.resourceHint(), examples);
    }
}
