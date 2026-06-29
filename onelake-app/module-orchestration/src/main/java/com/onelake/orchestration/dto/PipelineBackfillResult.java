package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of {@link PipelineBackfillService} run (C4 — see docs/流水线模块重设计方案.md §7 P1).
 *
 * <p>{@code dryRun=true} → only {@code plannedItems} populated; nothing written.
 * <p>{@code dryRun=false} → {@code createdPipelineIds} contains newly created pipeline IDs;
 * {@code skippedModelIds} contains models that already had a pipeline task (idempotency).
 */
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

    public record BackfillItem(
            UUID modelId,
            String modelName,
            String modelNameHint,
            String sourceFqn,
            String targetFqn
    ) {}
}
