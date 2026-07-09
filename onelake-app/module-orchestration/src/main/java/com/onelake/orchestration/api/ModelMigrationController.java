package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.ModelMigrationResult;
import com.onelake.orchestration.service.ModelMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史模型迁移接口：把 {@code modeling.data_model} 中已校验的模型定义迁移为
 * Spark 流水线实体。
 *
 * <p>生产执行顺序：
 * <ol>
 *   <li>{@code GET /api/v1/orchestration/pipelines/model-migration}：干跑，审计待迁移清单。</li>
 *   <li>确认待迁移清单覆盖期望的 {@code modeling.data_model} 存量模型。</li>
 *   <li>{@code POST /api/v1/orchestration/pipelines/model-migration?dryRun=false}：执行迁移。</li>
 * </ol>
 *
 * <p>注意：旧 {@code /backfill} 路径仅作为一版兼容入口保留，不代表真正的数据回填能力。
 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines")
@RequiredArgsConstructor
@Slf4j
public class ModelMigrationController {

    private final ModelMigrationService modelMigrationService;

    /**
     * 新路径干跑接口，只扫描候选模型并返回迁移计划，不写入流水线表。
     */
    @GetMapping("/model-migration")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<ModelMigrationResult> migrationDryRun() {
        return ApiResponse.ok(modelMigrationService.migrate(true));
    }

    /**
     * 新路径执行接口；默认执行真实迁移，可通过 {@code dryRun=true} 复用干跑行为。
     */
    @PostMapping("/model-migration")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<ModelMigrationResult> migrationExecute(@RequestParam(defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(modelMigrationService.migrate(dryRun));
    }

    /**
     * @deprecated 请改用 {@link #migrationDryRun()}。该兼容入口保留一版后可删除。
     */
    @Deprecated(forRemoval = false)
    @GetMapping("/backfill")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<ModelMigrationResult> legacyBackfillDryRun() {
        log.warn("已调用弃用接口 /api/v1/orchestration/pipelines/backfill；"
                + "请改用 /api/v1/orchestration/pipelines/model-migration。"
                + "该兼容路径保留一版后可删除。");
        return ApiResponse.ok(modelMigrationService.migrate(true));
    }

    /**
     * @deprecated 请改用 {@link #migrationExecute(boolean)}。该兼容入口保留一版后可删除。
     */
    @Deprecated(forRemoval = false)
    @PostMapping("/backfill")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<ModelMigrationResult> legacyBackfillExecute(@RequestParam(defaultValue = "false") boolean dryRun) {
        log.warn("已调用弃用接口 /api/v1/orchestration/pipelines/backfill；"
                + "请改用 /api/v1/orchestration/pipelines/model-migration。"
                + "该兼容路径保留一版后可删除。");
        return ApiResponse.ok(modelMigrationService.migrate(dryRun));
    }
}
