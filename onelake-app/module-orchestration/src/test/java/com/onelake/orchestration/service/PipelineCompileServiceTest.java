package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineCompileService} 的 P1 单元测试。
 *
 * <p>覆盖 C1 单一事实来源、C2 基于 PIPELINE 边的拓扑排序、环路检测和节点级校验。
 */
@ExtendWith(MockitoExtension.class)
class PipelineCompileServiceTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;

    private PipelineCompileService service;

    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        service = new PipelineCompileService(dagRepo, taskRepo, edgeRepo);
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void teardown() {
        TenantContext.clear();
    }

    @Test
    void compilesTwoSparkSqlTasksInTopoOrder() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask upstream = sparkSqlTask("t_upstream", "iceberg.dwd.orders");
        PipelineTask downstream = sparkSqlTask("t_downstream", "iceberg.dwd.order_items");
        downstream.setConfig("{\"sql\":\"select ${upstream.t_upstream.rowsWritten}\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId))
                .thenReturn(List.of(upstream, downstream));

        PipelineTaskEdge edge = edge("t_upstream", "t_downstream", EdgeLayer.PIPELINE);
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.graphErrors()).isEmpty();
        // 拓扑顺序应为上游在前、下游在后。
        assertThat(result.tasks()).extracting("taskKey")
                .containsExactly("t_upstream", "t_downstream");
        assertThat(result.pipelineTag()).startsWith("pipeline_");
    }

    @Test
    void rejectsUpstreamOutputReferenceWithoutPipelineAncestry() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask sibling = sparkSqlTask("sibling", "iceberg.dwd.sibling");
        PipelineTask consumer = sparkSqlTask("consumer", "iceberg.dwd.consumer");
        consumer.setConfig("{\"sql\":\"select ${upstream.sibling.rowsWritten}\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId))
                .thenReturn(List.of(sibling, consumer));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors())
                .contains("Task consumer references non-upstream task: sibling");
    }

    @Test
    void detectsCycleInPipelineLayer() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask a = sparkSqlTask("a", "iceberg.dwd.a");
        PipelineTask b = sparkSqlTask("b", "iceberg.dwd.b");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(a, b));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("a", "b", EdgeLayer.PIPELINE),
                edge("b", "a", EdgeLayer.PIPELINE)));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors()).anyMatch(e -> e.toLowerCase().contains("cycle"));
    }

    @Test
    void rejectsSparkSqlWithoutTargetFqn() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_bad", null);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("targetFqn");
    }

    @Test
    void rejectsSparkSqlWithoutSqlConfig() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_missing_sql", "iceberg.dwd.c1");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.sql");
    }

    @Test
    void rejectsInvalidDynamicTimeExpressionDuringCompile() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_invalid_param", "iceberg.dwd.invalid_param");
        t.setConfig("{\"sql\":\"SELECT '${bizdate--1}'\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage())
                .contains("parameter expression invalid")
                .contains("bizdate--1");
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.FAILED);
    }

    @Test
    void pysparkRequiresScriptConfig() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("py_task", "iceberg.dwd.py");
        t.setTaskType(TaskType.PYSPARK);
        t.setEngine("PYSPARK");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.script");
    }

    @Test
    void sparkTaskWithTargetFqnCompilesAsExecutable() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("spark_etl");
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName("spark_etl");
        t.setEngine("SPARK_SQL");
        t.setTargetFqn("iceberg.dwd.spark_etl");
        t.setConfig("{\"sql\":\"SELECT 1\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isTrue();
        assertThat(result.tasks().get(0).errorMessage()).isNull();
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.VALIDATED);
        assertThat(t.getExecutable()).isTrue();
    }

    @Test
    void rejectsWhenPipelineNotFound() {
        when(dagRepo.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.compile(dagId))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsWhenTenantContextMissing() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.compile(dagId))
                .isInstanceOf(BizException.class);
        TenantContext.setTenantId(tenantId); // 为 @AfterEach 恢复租户上下文。
    }

    @Test
    void flagsDanglingEdgesAsGraphErrors() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask t = sparkSqlTask("t_solo", "iceberg.dwd.solo");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("t_solo", "t_missing", EdgeLayer.PIPELINE)));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.graphErrors()).anyMatch(e -> e.contains("t_missing"));
    }

    @Test
    void syncRefTaskRequiresTargetFqnOnly() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("sync_ref");
        t.setTaskType(TaskType.SYNC_REF);
        t.setName("sync_ref");
        t.setEngine("SPARK");
        t.setConfig("{}");
        // 缺少 targetFqn。
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("targetFqn");
    }

    @Test
    void syncRefTaskAllowsMissingSyncTaskIdWhenTargetFqnIsPresent() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("sync_ref");
        t.setTaskType(TaskType.SYNC_REF);
        t.setName("sync_ref");
        t.setEngine("SPARK");
        t.setTargetFqn("iceberg.ods.orders");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isTrue();
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.VALIDATED);
        assertThat(t.getExecutable()).isFalse();
    }

    // ---------- 辅助方法 ----------

    private Dag newPipelineDag() {
        Dag d = new Dag();
        d.setId(dagId);
        d.setTenantId(tenantId);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setPipelineKind("BLANK");
        d.setStatus("DRAFT");
        d.setEngine("SPARK");
        return d;
    }

    private PipelineTask sparkSqlTask(String key, String targetFqn) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(key);
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName(key);
        t.setEngine("SPARK");
        t.setConfig("{\"sql\":\"select * from source\"}");
        t.setTargetFqn(targetFqn);
        return t;
    }

    private PipelineTaskEdge edge(String src, String tgt, EdgeLayer layer) {
        PipelineTaskEdge e = new PipelineTaskEdge();
        e.setTenantId(tenantId);
        e.setDagId(dagId);
        e.setSourceKey(src);
        e.setTargetKey(tgt);
        e.setEdgeLayer(layer);
        e.setSourcePort("out");
        e.setTargetPort("in");
        return e;
    }
}
