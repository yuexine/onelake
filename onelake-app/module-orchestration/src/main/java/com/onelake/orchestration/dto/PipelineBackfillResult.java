package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 旧模型迁移结果别名。
 *
 * @param dryRun 是否为干跑
 * @param totalCandidates 扫描到的候选模型总数
 * @param plannedItems 本次计划迁移的旧计划项列表
 * @param createdPipelineIds 实际创建的流水线 ID
 * @param skippedModelIds 已存在流水线引用而跳过的模型 ID
 * @param errors 迁移过程中收集的错误信息
 * @deprecated 请使用 {@link ModelMigrationResult}。历史过程迁移的是模型结构，
 * 不是按分区或业务日期展开的数据回填。
 */
@Deprecated(forRemoval = false)
public record PipelineBackfillResult(
        boolean dryRun,
        int totalCandidates,
        List<BackfillItem> plannedItems,
        List<UUID> createdPipelineIds,
        List<UUID> skippedModelIds,
        List<String> errors
) {

    public PipelineBackfillResult {
        plannedItems = plannedItems == null ? List.of() : List.copyOf(plannedItems);
        createdPipelineIds = createdPipelineIds == null ? List.of() : List.copyOf(createdPipelineIds);
        skippedModelIds = skippedModelIds == null ? List.of() : List.copyOf(skippedModelIds);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public PipelineBackfillResult(ModelMigrationResult result) {
        this(
                result.dryRun(),
                result.totalCandidates(),
                result.plannedItems().stream()
                        .map(BackfillItem::new)
                        .toList(),
                result.createdPipelineIds(),
                result.skippedModelIds(),
                result.errors()
        );
    }

    public ModelMigrationResult toModelMigrationResult() {
        return new ModelMigrationResult(
                dryRun,
                totalCandidates,
                plannedItems.stream()
                        .map(BackfillItem::toMigrationItem)
                        .toList(),
                createdPipelineIds,
                skippedModelIds,
                errors
        );
    }

    /**
     * 旧模型迁移计划项别名。
     *
     * @param modelId 历史模型 ID
     * @param modelName 历史模型名称
     * @param modelNameHint dbt 模型名等辅助命名线索
     * @param sourceFqn ODS 源表 FQN
     * @param targetFqn DWD 目标表 FQN
     * @deprecated 请使用 {@link ModelMigrationResult.MigrationItem}。
     */
    @Deprecated(forRemoval = false)
    public record BackfillItem(
            UUID modelId,
            String modelName,
            String modelNameHint,
            String sourceFqn,
            String targetFqn
    ) {
        public BackfillItem(ModelMigrationResult.MigrationItem item) {
            this(
                    item.modelId(),
                    item.modelName(),
                    item.modelNameHint(),
                    item.sourceFqn(),
                    item.targetFqn()
            );
        }

        public ModelMigrationResult.MigrationItem toMigrationItem() {
            return new ModelMigrationResult.MigrationItem(
                    modelId,
                    modelName,
                    modelNameHint,
                    sourceFqn,
                    targetFqn
            );
        }
    }
}
