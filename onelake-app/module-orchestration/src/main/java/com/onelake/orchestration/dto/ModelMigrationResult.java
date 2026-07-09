package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 历史模型迁移结果。
 *
 * <p>{@code dryRun=true} 时只返回 {@code plannedItems}，不会写入流水线实体。
 * <p>{@code dryRun=false} 时 {@code createdPipelineIds} 返回新建流水线 ID，
 * {@code skippedModelIds} 返回因幂等检查而跳过的模型 ID。
 *
 * @param dryRun 是否为干跑
 * @param totalCandidates 扫描到的候选模型总数
 * @param plannedItems 本次计划迁移的模型明细
 * @param createdPipelineIds 实际创建的流水线 ID
 * @param skippedModelIds 已存在流水线引用而跳过的模型 ID
 * @param errors 迁移过程中收集的错误信息
 */
public record ModelMigrationResult(
        boolean dryRun,
        int totalCandidates,
        List<MigrationItem> plannedItems,
        List<UUID> createdPipelineIds,
        List<UUID> skippedModelIds,
        List<String> errors
) {

    public ModelMigrationResult {
        plannedItems = plannedItems == null ? List.of() : List.copyOf(plannedItems);
        createdPipelineIds = createdPipelineIds == null ? List.of() : List.copyOf(createdPipelineIds);
        skippedModelIds = skippedModelIds == null ? List.of() : List.copyOf(skippedModelIds);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * 单个历史模型的迁移计划项。
     *
     * @param modelId 历史模型 ID
     * @param modelName 历史模型名称
     * @param modelNameHint dbt 模型名等辅助命名线索
     * @param sourceFqn ODS 源表 FQN
     * @param targetFqn DWD 目标表 FQN
     */
    public record MigrationItem(
            UUID modelId,
            String modelName,
            String modelNameHint,
            String sourceFqn,
            String targetFqn
    ) {}
}
