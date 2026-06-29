package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 tests for {@link PipelineBackfillService} — C4 (docs/流水线模块重设计方案.md §7 P1).
 *
 * <p>Verifies dry-run produces a planned list without writes; idempotency skips models
 * already covered by a pipeline_task; execute path creates Dag + SYNC_REF + SPARK_SQL +
 * PIPELINE edge per model.
 */
@ExtendWith(MockitoExtension.class)
class PipelineBackfillServiceTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private JdbcTemplate jdbc;

    private PipelineBackfillService service;
    private UUID tenantId;

    @BeforeEach
    void setup() {
        service = new PipelineBackfillService(dagRepo, taskRepo, edgeRepo, jdbc);
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        // Dag save returns the same dag (with id simulated)
        lenient().doAnswer(inv -> {
            Dag d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        }).when(dagRepo).save(any(Dag.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void dryRunProducesPlanWithoutWriting() {
        UUID m1 = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(
                        modelRow(m1, "dwd_orders", "ods.orders", "dwd.orders")));

        var result = service.backfill(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.plannedItems()).hasSize(1);
        assertThat(result.plannedItems().get(0).modelId()).isEqualTo(m1);
        assertThat(result.createdPipelineIds()).isEmpty();
        verify(taskRepo, never()).save(any(PipelineTask.class));
        verify(edgeRepo, never()).save(any(PipelineTaskEdge.class));
    }

    @Test
    void executeCreatesPipelinePerModelWithSyncAndSparkTasks() {
        UUID m1 = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(modelRow(m1, "dwd_orders", "ods.orders", "dwd.orders")));
        when(taskRepo.countByTenantIdAndModelId(tenantId, m1)).thenReturn(0L);

        var result = service.backfill(false);

        assertThat(result.createdPipelineIds()).hasSize(1);
        verify(dagRepo).save(any(Dag.class));
        // 2 tasks per model: SYNC_REF + SPARK_SQL
        ArgumentCaptor<PipelineTask> taskCaptor = ArgumentCaptor.forClass(PipelineTask.class);
        verify(taskRepo, org.mockito.Mockito.times(2)).save(taskCaptor.capture());
        List<PipelineTask> tasks = taskCaptor.getAllValues();
        assertThat(tasks).extracting(PipelineTask::getTaskType)
                .containsExactlyInAnyOrder(
                        com.onelake.orchestration.domain.enums.TaskType.SYNC_REF,
                        com.onelake.orchestration.domain.enums.TaskType.SPARK_SQL);
        // SPARK_SQL task keeps modelId only for migration idempotency.
        PipelineTask sparkTask = tasks.stream()
                .filter(t -> t.getTaskType() == com.onelake.orchestration.domain.enums.TaskType.SPARK_SQL)
                .findFirst().orElseThrow();
        assertThat(sparkTask.getModelId()).isEqualTo(m1);
        assertThat(sparkTask.getEngine()).isEqualTo("SPARK_SQL");
        assertThat(sparkTask.getConfig()).contains("\"nodeKind\":\"SINK\"");
        // SYNC_REF task has the source FQN
        PipelineTask syncRef = tasks.stream()
                .filter(t -> t.getTaskType() == com.onelake.orchestration.domain.enums.TaskType.SYNC_REF)
                .findFirst().orElseThrow();
        assertThat(syncRef.getTargetFqn()).isEqualTo("ods.orders");
        // 1 PIPELINE edge
        verify(edgeRepo).save(any(PipelineTaskEdge.class));
    }

    @Test
    void skipsModelsAlreadyReferencedByPipelineTask() {
        UUID m1 = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(modelRow(m1, "dwd_orders", "ods.orders", "dwd.orders")));
        when(taskRepo.countByTenantIdAndModelId(tenantId, m1)).thenReturn(1L); // already exists

        var result = service.backfill(false);

        assertThat(result.skippedModelIds()).containsExactly(m1);
        assertThat(result.createdPipelineIds()).isEmpty();
        verify(dagRepo, never()).save(any(Dag.class));
    }

    @Test
    void skipsModelsWithoutTargetFqn() {
        UUID m1 = UUID.randomUUID();
        Map<String, Object> row = modelRow(m1, "dwd_x", "ods.x", "dwd.x");
        row.put("target_fqn", null); // missing
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(row));

        var result = service.backfill(false);

        assertThat(result.createdPipelineIds()).isEmpty();
        assertThat(result.errors()).anyMatch(e -> e.contains("target_fqn"));
    }

    @Test
    void rejectsWhenTenantContextMissing() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.backfill(true))
                .isInstanceOf(BizException.class);
        TenantContext.setTenantId(tenantId);
    }

    private Map<String, Object> modelRow(UUID id, String name, String sourceFqn, String targetFqn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("dbt_model_name", name);
        m.put("source_fqn", sourceFqn);
        m.put("target_fqn", targetFqn);
        m.put("status", "VALIDATED");
        return m;
    }
}
