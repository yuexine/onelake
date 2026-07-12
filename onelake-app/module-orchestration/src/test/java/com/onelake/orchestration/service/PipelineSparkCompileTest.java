package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spark 流水线编译测试。
 *
 * <p>验证输入由显式流水线边解析，Spark 节点会编译为可执行的 Dagster
 * {@code run_spark_task_op} 输入。
 */
@ExtendWith(MockitoExtension.class)
class PipelineSparkCompileTest {

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
    void clear() {
        TenantContext.clear();
    }

    @Test
    void doesNotDeriveImplicitEdgeFromFqnMatching() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask upstream = sparkTask("spark_orders", "iceberg.ods.orders");
        upstream.setTargetFqn("iceberg.dwd.orders");
        PipelineTask spark = sparkTask("spark_agg", "iceberg.dwd.orders");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(upstream, spark));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.tasks()).extracting("taskKey")
                .containsExactlyInAnyOrder("spark_orders", "spark_agg");
        assertThat(result.tasks()).filteredOn(t -> t.taskKey().equals("spark_agg"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.valid()).isTrue();
                    assertThat(t.errorMessage()).isNull();
                });
        verify(taskRepo).saveAll(any());
        verify(edgeRepo, never()).save(any(PipelineTaskEdge.class));
    }

    @Test
    void validatesExplicitSparkToSparkPipelineDependency() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask spark = sparkTask("spark_first", "iceberg.ods.upstream");
        spark.setTargetFqn("iceberg.dwd.upstream");
        PipelineTask downstream = sparkTask("spark_downstream", "iceberg.dwd.upstream");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(spark, downstream));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge("spark_first", "spark_downstream", "in", "src")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.graphErrors()).isEmpty();
    }

    @Test
    void validatesTrinoOutputConsumedBySpark() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask trino = trinoTask("trino_mid", "iceberg.dwd.trino_mid");
        trino.setEngine(null);
        PipelineTask spark = sparkTask("spark_consume", "iceberg.dwd.trino_mid");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(trino, spark));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge("trino_mid", "spark_consume", "in", "mid")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.graphErrors()).isEmpty();
        assertThat(trino.getExecutable()).isTrue();
        assertThat(spark.getExecutable()).isTrue();
        assertThat(PipelineNodePortRegistry.contractFor(trino).engine()).isEqualTo("TRINO");
    }

    @Test
    void sparkTaskWithScriptCompilesAsExecutable() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask spark = new PipelineTask();
        spark.setId(UUID.randomUUID());
        spark.setTenantId(tenantId);
        spark.setDagId(dagId);
        spark.setTaskKey("spark_etl");
        spark.setTaskType(TaskType.SPARK_SQL);
        spark.setName("spark_etl");
        spark.setEngine("SPARK_SQL");
        spark.setTargetFqn("iceberg.dwd.spark_etl");
        spark.setConfig("{\"sql\":\"SELECT count(*) FROM iceberg.ods.orders\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(spark));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isTrue();
        assertThat(result.tasks().get(0).errorMessage()).isNull();
        assertThat(spark.getExecutable()).isTrue();
        verify(taskRepo).saveAll(any());
    }

    @Test
    void derivesSparkJoinInputsAndSqlFromPipelineEdges() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask user = syncRefTask("sync_user", "onelake.ods.user");
        PipelineTask profile = syncRefTask("sync_profile", "onelake.ods.user_profile");
        PipelineTask join = new PipelineTask();
        join.setId(UUID.randomUUID());
        join.setTenantId(tenantId);
        join.setDagId(dagId);
        join.setTaskKey("spark_join_user_profile");
        join.setTaskType(TaskType.SPARK_SQL);
        join.setName("Spark 用户档案关联");
        join.setEngine("SPARK_SQL");
        join.setTargetFqn("onelake.dwd.user_profile_wide");
        join.setConfig("""
            {
              "dataflow": {
                "nodeKind": "JOIN",
                "joinType": "LEFT",
                "leftAlias": "u",
                "rightAlias": "p",
                "on": "u.user_id = p.user_id",
                "select": "u.user_id, u.user_name, p.description"
              }
            }
            """);
        PipelineTaskEdge left = edge("sync_user", "spark_join_user_profile", "left", "u");
        PipelineTaskEdge right = edge("sync_profile", "spark_join_user_profile", "right", "p");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(user, profile, join));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(left, right));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(join.getConfig()).contains("\"from_tables\":[\"onelake.ods.user\",\"onelake.ods.user_profile\"]");
        assertThat(join.getConfig()).contains("CREATE OR REPLACE TABLE onelake.dwd.user_profile_wide AS");
        assertThat(join.getConfig()).contains("LEFT JOIN onelake.ods.user_profile p ON u.user_id = p.user_id");
        assertThat(join.getExecutable()).isTrue();
    }

    @Test
    void sparkJoinWithoutBothInputsFailsValidation() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask user = syncRefTask("sync_user", "onelake.ods.user");
        PipelineTask join = new PipelineTask();
        join.setId(UUID.randomUUID());
        join.setTenantId(tenantId);
        join.setDagId(dagId);
        join.setTaskKey("spark_join_missing_right");
        join.setTaskType(TaskType.SPARK_SQL);
        join.setName("Spark 缺右表 Join");
        join.setEngine("SPARK_SQL");
        join.setTargetFqn("onelake.dwd.user_profile_wide");
        join.setConfig("{\"dataflow\":{\"nodeKind\":\"JOIN\",\"joinType\":\"LEFT\",\"on\":\"u.id = p.id\"}}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(user, join));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge("sync_user", "spark_join_missing_right", "left", "u")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).filteredOn(t -> t.taskKey().equals("spark_join_missing_right"))
                .singleElement()
                .satisfies(t -> assertThat(t.errorMessage()).contains("config.sql"));
        assertThat(join.getExecutable()).isFalse();
    }

    @Test
    void sparkJoinRejectsUnknownInputPort() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask user = syncRefTask("sync_user", "onelake.ods.user");
        PipelineTask profile = syncRefTask("sync_profile", "onelake.ods.user_profile");
        PipelineTask join = sparkJoinTask("spark_join_bad_port");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(user, profile, join));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("sync_user", "spark_join_bad_port", "left", "u"),
                edge("sync_profile", "spark_join_bad_port", "inputs", "p")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors())
                .anyMatch(e -> e.contains("target port 'inputs' is not declared"));
    }

    @Test
    void sparkJoinRejectsDuplicateSingleInputPort() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask user = syncRefTask("sync_user", "onelake.ods.user");
        PipelineTask profile = syncRefTask("sync_profile", "onelake.ods.user_profile");
        PipelineTask join = sparkJoinTask("spark_join_duplicate_left");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(user, profile, join));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("sync_user", "spark_join_duplicate_left", "left", "u"),
                edge("sync_profile", "spark_join_duplicate_left", "left", "p")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors())
                .anyMatch(e -> e.contains("input port 'left' allows at most 1 edge"));
        assertThat(result.graphErrors())
                .anyMatch(e -> e.contains("missing required input port 'right'"));
    }

    @Test
    void fanOutFromOneSourceToTwoSparkTargetsIsValid() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask source = syncRefTask("sync_user", "onelake.ods.user");
        PipelineTask sparkA = sparkTask("spark_a", "onelake.ods.user");
        PipelineTask sparkB = sparkTask("spark_b", "onelake.ods.user");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(source, sparkA, sparkB));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("sync_user", "spark_a", "in", "u"),
                edge("sync_user", "spark_b", "in", "u")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.graphErrors()).isEmpty();
    }

    @Test
    void derivesColumnsAndSqlFromSingleInputEdge() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask source = syncRefTask("sync_joined_user", "onelake.dwd.user_joined");
        PipelineTask derive = new PipelineTask();
        derive.setId(UUID.randomUUID());
        derive.setTenantId(tenantId);
        derive.setDagId(dagId);
        derive.setTaskKey("derive_user_uuid");
        derive.setTaskType(TaskType.SPARK_SQL);
        derive.setName("派生用户 UUID");
        derive.setEngine("SPARK_SQL");
        derive.setTargetFqn("onelake.dwd.user_enriched");
        derive.setConfig("""
            {
              "dataflow": {
                "nodeKind": "DERIVE_COLUMN",
                "sourceAlias": "src",
                "deriveColumns": [
                  {"name":"用户 UUID","expression":"uuid()"},
                  {"name":"description","expression":"trim(src.description)"}
                ]
              }
            }
            """);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(source, derive));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge("sync_joined_user", "derive_user_uuid", "in", "src")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(derive.getConfig()).contains("\"from_tables\":[\"onelake.dwd.user_joined\"]");
        assertThat(derive.getConfig()).contains("CREATE OR REPLACE TABLE onelake.dwd.user_enriched AS");
        assertThat(derive.getConfig()).contains("uuid() AS `用户 UUID`");
        assertThat(derive.getConfig()).contains("trim(src.description) AS `description`");
        assertThat(derive.getExecutable()).isTrue();
    }

    @Test
    void sinkGeneratesDwdWriteSqlFromSingleInputEdge() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask source = syncRefTask("derive_user_uuid", "onelake.tmp.user_enriched");
        PipelineTask sink = new PipelineTask();
        sink.setId(UUID.randomUUID());
        sink.setTenantId(tenantId);
        sink.setDagId(dagId);
        sink.setTaskKey("sink_dwd_user");
        sink.setTaskType(TaskType.SPARK_SQL);
        sink.setName("落 DWD 用户表");
        sink.setEngine("SPARK_SQL");
        sink.setTargetFqn("onelake.dwd.user");
        sink.setConfig("""
            {
              "dataflow": {
                "nodeKind": "SINK",
                "sourceAlias": "s",
                "mode": "OVERWRITE"
              }
            }
            """);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(source, sink));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge("derive_user_uuid", "sink_dwd_user", "in", "s")));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(sink.getConfig()).contains("\"from_tables\":[\"onelake.tmp.user_enriched\"]");
        assertThat(sink.getConfig()).contains("CREATE OR REPLACE TABLE onelake.dwd.user AS");
        assertThat(sink.getConfig()).contains("SELECT s.*");
        assertThat(sink.getConfig()).contains("FROM onelake.tmp.user_enriched s");
        assertThat(sink.getExecutable()).isTrue();
    }

    @Test
    void qualityGateWithRulesCompilesAsExecutable() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask gate = new PipelineTask();
        gate.setId(UUID.randomUUID());
        gate.setTenantId(tenantId);
        gate.setDagId(dagId);
        gate.setTaskKey("quality_gate");
        gate.setTaskType(TaskType.QUALITY_GATE);
        gate.setName("质量门禁");
        gate.setEngine("SPARK_SQL");
        gate.setTargetFqn("onelake.dwd.user");
        gate.setConfig("""
            {
              "targetModelFqn": "onelake.dwd.user",
              "gates": [
                {"id":"primary","kind":"PRIMARY","enabled":true,"columns":["user_id"],"actionOnViolation":"FAIL"}
              ]
            }
            """);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(gate));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.tasks()).singleElement().satisfies(t -> {
            assertThat(t.taskKey()).isEqualTo("quality_gate");
            assertThat(t.valid()).isTrue();
        });
        assertThat(gate.getExecutable()).isTrue();
    }

    @Test
    void qualityGateWithoutRulesFailsValidation() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask gate = new PipelineTask();
        gate.setId(UUID.randomUUID());
        gate.setTenantId(tenantId);
        gate.setDagId(dagId);
        gate.setTaskKey("quality_gate_empty");
        gate.setTaskType(TaskType.QUALITY_GATE);
        gate.setName("空质量门禁");
        gate.setEngine("SPARK_SQL");
        gate.setTargetFqn("onelake.dwd.user");
        gate.setConfig("{\"targetModelFqn\":\"onelake.dwd.user\",\"gates\":[]}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(gate));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).singleElement()
                .satisfies(t -> assertThat(t.errorMessage()).contains("config.gates"));
        assertThat(gate.getExecutable()).isFalse();
    }

    @Test
    void sparkTaskWithoutScriptFails() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask spark = new PipelineTask();
        spark.setId(UUID.randomUUID());
        spark.setTenantId(tenantId);
        spark.setDagId(dagId);
        spark.setTaskKey("spark_empty");
        spark.setTaskType(TaskType.SPARK_SQL);
        spark.setName("spark_empty");
        spark.setEngine("SPARK_SQL");
        spark.setConfig("{}");  // 缺少 sql/script。
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(spark));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.sql");
    }

    // ---------- 辅助方法 ----------

    private Dag pipelineDag() {
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

    private PipelineTask syncRefTask(String key, String targetFqn) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(key);
        t.setTaskType(TaskType.SYNC_REF);
        t.setName(key);
        t.setEngine("SPARK");
        t.setTargetFqn(targetFqn);
        t.setConfig("{}");
        return t;
    }

    private PipelineTask sparkJoinTask(String key) {
        PipelineTask join = new PipelineTask();
        join.setId(UUID.randomUUID());
        join.setTenantId(tenantId);
        join.setDagId(dagId);
        join.setTaskKey(key);
        join.setTaskType(TaskType.SPARK_SQL);
        join.setName(key);
        join.setEngine("SPARK_SQL");
        join.setTargetFqn("onelake.dwd." + key);
        join.setConfig("""
            {
              "dataflow": {
                "nodeKind": "JOIN",
                "joinType": "LEFT",
                "leftAlias": "u",
                "rightAlias": "p",
                "on": "u.user_id = p.user_id",
                "select": "u.user_id, p.description"
              }
            }
            """);
        return join;
    }

    private PipelineTaskEdge edge(String sourceKey, String targetKey, String targetInput, String alias) {
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
        edge.setInputAlias(alias);
        edge.setJoinRole(targetInput);
        edge.setTriggerPolicy("ALL_SUCCEEDED");
        edge.setFreshnessPolicy("LATEST");
        return edge;
    }

    private PipelineTask sparkTask(String key, String fromTableFqn) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(key);
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName(key);
        t.setEngine("SPARK_SQL");
        t.setTargetFqn("iceberg.dwd.spark_" + key);
        t.setConfig("{\"sql\":\"SELECT count(*) FROM " + fromTableFqn + "\",\"from_tables\":[\"" + fromTableFqn + "\"]}");
        return t;
    }

    private PipelineTask trinoTask(String key, String targetFqn) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(key);
        t.setTaskType(TaskType.TRINO_SQL);
        t.setName(key);
        t.setEngine("TRINO");
        t.setTargetFqn(targetFqn);
        t.setConfig("{\"sql\":\"CREATE TABLE dwd.trino_mid AS SELECT 1 AS id\","
                + "\"catalog\":\"iceberg\",\"schema\":\"dwd\"}");
        return t;
    }

}
