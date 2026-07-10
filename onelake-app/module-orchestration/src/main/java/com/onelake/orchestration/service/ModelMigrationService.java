package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.ModelMigrationResult;
import com.onelake.orchestration.dto.ModelMigrationResult.MigrationItem;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 历史模型迁移服务：把 {@code modeling.data_model} 存量记录迁移成流水线 V2 实体
 * （{@code orchestration.dag + pipeline_task + pipeline_task_edge}）。
 *
 * <p><b>模型迁移</b>：面向历史治理模型的一次性结构迁移，使存量模型可以进入编排模块的
 * 观测与触发链路。它不是按分区或业务日期展开的数据回填。
 *
 * <p><b>迁移策略</b>：每个 {@code VALIDATED} 状态的 {@code modeling.data_model}
 * 创建一条仅使用 Spark 执行的 {@code BLANK} 流水线：
 * <ul>
 *   <li>一个 {@code SYNC_REF} 节点指向 {@code model.source_fqn}，承接 ODS 表就绪事件。</li>
 *   <li>一个 {@code SPARK_SQL} 目标写入节点指向目标 DWD 表。</li>
 *   <li>一条 {@code PIPELINE} 边：{@code SYNC_REF -> SPARK_SQL}。</li>
 * </ul>
 *
 * <p><b>幂等口径</b>：以 {@code (tenant_id, model_id)} 为准；若已有
 * {@code pipeline_task} 引用该模型，则跳过该模型。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelMigrationService {

    private static final String KIND_BLANK = "BLANK";
    private static final String STATUS_VALIDATED = "VALIDATED";
    private static final String ENGINE_SPARK = "SPARK";
    private static final String ENGINE_SPARK_SQL = "SPARK_SQL";

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final JdbcTemplate jdbc;

    /**
     * 扫描并按需执行迁移。建议先以 {@code dryRun=true} 干跑审计候选清单。
     *
     * @param dryRun true 仅生成计划，false 在同一事务中创建流水线实体
     * @return 候选、计划、创建、跳过和错误的完整迁移报告
     */
    @Transactional
    public ModelMigrationResult migrate(boolean dryRun) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required for model migration");
        }

        List<Map<String, Object>> models = jdbc.queryForList("""
            SELECT id, name, dbt_model_name, source_fqn, target_fqn, status
            FROM modeling.data_model
            WHERE tenant_id = ? AND upper(status) = ?
            ORDER BY created_at
            """, tenantId, STATUS_VALIDATED);

        List<MigrationItem> planned = new ArrayList<>();
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

            // 幂等检查：只要当前租户下已有 pipeline_task 引用该模型，就不重复创建流水线。
            if (taskRepo.countByTenantIdAndModelId(tenantId, modelId) > 0) {
                skippedIds.add(modelId);
                continue;
            }

            planned.add(new MigrationItem(modelId, name, modelNameHint, sourceFqn, targetFqn));

            if (dryRun) {
                continue;
            }

            try {
                UUID pipelineId = createPipelineForModel(tenantId, modelId, name,
                        modelNameHint, sourceFqn, targetFqn);
                createdIds.add(pipelineId);
                log.info("历史模型 {} 已迁移为流水线 {}", modelId, pipelineId);
            } catch (RuntimeException e) {
                errors.add("failed to migrate model " + modelId + ": " + e.getMessage());
                log.warn("历史模型 {} 迁移失败：{}", modelId, e.getMessage());
            }
        }

        log.info("模型迁移完成 dryRun={} tenant={} candidates={} planned={} created={} skipped={} errors={}",
                dryRun, tenantId, models.size(), planned.size(), createdIds.size(),
                skippedIds.size(), errors.size());

        return new ModelMigrationResult(
                dryRun, models.size(), planned, createdIds, skippedIds, errors);
    }

    /** 将单个历史模型转换为 SYNC_REF -> SPARK_SQL 的最小可运行流水线。 */
    private UUID createPipelineForModel(UUID tenantId, UUID modelId, String modelName,
                                        String modelNameHint, String sourceFqn, String targetFqn) {
        // 1. 创建流水线主体。历史模型迁移出的流水线默认保持 VALIDATED，后续可直接纳入发布/运行链路。
        Dag dag = new Dag();
        dag.setTenantId(tenantId);
        dag.setName("model_migration_" + sanitize(modelName) + "_" + modelNameSuffix(modelId));
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

        // 2. 创建 SYNC_REF 源节点，用 target_fqn 关联 ODS 源表就绪事件。
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
        // 字段 sync_task_id 故意为空：迁移场景只需要 target_fqn 参与事件匹配。
        syncTask = taskRepo.save(syncTask);

        // 3. 创建 SPARK_SQL 目标写入节点，保留 model_id 作为后续幂等判断与审计线索。
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

        // 4. 建立流水线边：ODS 源表就绪后驱动 DWD Spark Sink。
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
