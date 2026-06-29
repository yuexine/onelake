package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineBackfillResult;
import com.onelake.orchestration.dto.PipelineBackfillResult.BackfillItem;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfills historical {@code modeling.data_model} rows into pipeline v2 entities
 * (orchestration.dag + pipeline_task + pipeline_task_edge).
 *
 * <p><b>C4 (docs/流水线模块重设计方案.md §7 P1 / §6.5)</b>: backfills historical
 * {@code modeling.data_model} rows into unified Spark pipeline entities so existing
 * governance models can be observed and triggered through orchestration.
 *
 * <p><b>Strategy</b>: for each VALIDATED {@code modeling.data_model}, create one
 * Spark-only {@code BLANK} pipeline with:
 * <ul>
 *   <li>One {@code SYNC_REF} task pointing at {@code model.source_fqn} so the
 *       {@code integration.table.loaded} event is handled by orchestration.</li>
 *   <li>One {@code SPARK_SQL} sink task pointing at the target table.</li>
 *   <li>One {@code PIPELINE} edge: SYNC_REF → SPARK_SQL.</li>
 * </ul>
 *
 * <p><b>Idempotent</b>: by {@code (tenant_id, model_id)}. If a pipeline_task already
 * references the model, skip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineBackfillService {

    private static final String KIND_BLANK = "BLANK";
    private static final String STATUS_VALIDATED = "VALIDATED";
    private static final String ENGINE_SPARK = "SPARK";
    private static final String ENGINE_SPARK_SQL = "SPARK_SQL";

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final JdbcTemplate jdbc;

    /**
     * Scan and (optionally) backfill. Call with {@code dryRun=true} first to audit.
     */
    @Transactional
    public PipelineBackfillResult backfill(boolean dryRun) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required for pipeline backfill");
        }

        List<Map<String, Object>> models = jdbc.queryForList("""
            SELECT id, name, dbt_model_name, source_fqn, target_fqn, status
            FROM modeling.data_model
            WHERE tenant_id = ? AND upper(status) = ?
            ORDER BY created_at
            """, tenantId, STATUS_VALIDATED);

        List<BackfillItem> planned = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        List<UUID> skippedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> row : models) {
            UUID modelId = toUuid(row.get("id"));
            if (modelId == null) {
                errors.add("data_model row missing id: " + row);
                continue;
            }
            String name = str(row.get("name"));
            String modelNameHint = str(row.get("dbt_model_name"));
            String sourceFqn = str(row.get("source_fqn"));
            String targetFqn = str(row.get("target_fqn"));
            if (!StringUtils.hasText(sourceFqn)) {
                errors.add("model " + modelId + " (" + name + ") missing source_fqn; skipped");
                continue;
            }
            if (!StringUtils.hasText(targetFqn)) {
                errors.add("model " + modelId + " (" + name + ") missing target_fqn; skipped");
                continue;
            }

            // Idempotency check: skip if a pipeline_task already references this model
            if (taskRepo.countByTenantIdAndModelId(tenantId, modelId) > 0) {
                skippedIds.add(modelId);
                continue;
            }

            planned.add(new BackfillItem(modelId, name, modelNameHint, sourceFqn, targetFqn));

            if (dryRun) {
                continue;
            }

            try {
                UUID pipelineId = createPipelineForModel(tenantId, modelId, name,
                        modelNameHint, sourceFqn, targetFqn);
                createdIds.add(pipelineId);
                log.info("Backfilled model {} → pipeline {}", modelId, pipelineId);
            } catch (RuntimeException e) {
                errors.add("failed to backfill model " + modelId + ": " + e.getMessage());
                log.warn("Backfill failed for model {}: {}", modelId, e.getMessage());
            }
        }

        log.info("Pipeline backfill dryRun={} tenant={} candidates={} planned={} created={} skipped={} errors={}",
                dryRun, tenantId, models.size(), planned.size(), createdIds.size(),
                skippedIds.size(), errors.size());

        return new PipelineBackfillResult(
                dryRun, models.size(), planned, createdIds, skippedIds, errors);
    }

    private UUID createPipelineForModel(UUID tenantId, UUID modelId, String modelName,
                                        String modelNameHint, String sourceFqn, String targetFqn) {
        // 1. Create Dag (pipeline)
        Dag dag = new Dag();
        dag.setTenantId(tenantId);
        dag.setName("backfill_" + sanitize(modelName) + "_" + modelNameSuffix(modelId));
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
        dag.setScheduleCron(null);
        dag.setEnabled(true);
        dag.setVersion(1);
        dag.setPipelineKind(KIND_BLANK);
        dag.setStatus(STATUS_VALIDATED);
        dag.setEngine(ENGINE_SPARK);
        dag.setResourceGroup("spark-default");
        dag.setComputeProfile("spark-small");
        dag = dagRepo.save(dag);

        // 2. SYNC_REF task — points at ODS source table
        String syncKey = "sync_ref_" + sanitize(modelName);
        PipelineTask syncTask = new PipelineTask();
        syncTask.setTenantId(tenantId);
        syncTask.setDagId(dag.getId());
        syncTask.setTaskKey(syncKey);
        syncTask.setTaskType(TaskType.SYNC_REF);
        syncTask.setName("ods source: " + sourceFqn);
        syncTask.setEngine(ENGINE_SPARK_SQL);
        syncTask.setTargetFqn(sourceFqn);
        syncTask.setConfig("{}");
        // sync_task_id is intentionally null: SYNC_REF only needs target_fqn for event matching.
        syncTask = taskRepo.save(syncTask);

        // 3. SPARK_SQL sink task — points at the target DWD table.
        String sparkKey = "spark_dwd_" + sanitize(modelName);
        PipelineTask sparkTask = new PipelineTask();
        sparkTask.setTenantId(tenantId);
        sparkTask.setDagId(dag.getId());
        sparkTask.setTaskKey(sparkKey);
        sparkTask.setTaskType(TaskType.SPARK_SQL);
        sparkTask.setName(modelName + " → " + targetFqn);
        sparkTask.setEngine(ENGINE_SPARK_SQL);
        sparkTask.setTargetFqn(targetFqn);
        sparkTask.setModelId(modelId);
        sparkTask.setConfig("""
            {"dataflow":{"nodeKind":"SINK","sourceAlias":"src","mode":"OVERWRITE","select":"src.*"}}
            """.trim());
        taskRepo.save(sparkTask);

        // 4. PIPELINE edge: SYNC_REF → SPARK_SQL
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setTenantId(tenantId);
        edge.setDagId(dag.getId());
        edge.setSourceKey(syncKey);
        edge.setTargetKey(sparkKey);
        edge.setEdgeLayer(EdgeLayer.PIPELINE);
        edgeRepo.save(edge);

        return dag.getId();
    }

    private UUID toUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String str(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        return "null".equals(s) ? "" : s;
    }

    private String sanitize(String name) {
        if (!StringUtils.hasText(name)) return "model";
        return name.toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private String modelNameSuffix(UUID modelId) {
        return modelId.toString().substring(0, 8);
    }
}
