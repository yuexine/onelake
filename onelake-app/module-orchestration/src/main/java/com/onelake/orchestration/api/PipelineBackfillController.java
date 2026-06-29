package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineBackfillResult;
import com.onelake.orchestration.service.PipelineBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint for the C4 backfill procedure
 * (docs/流水线模块重设计方案.md §7 P1 / §6.5).
 *
 * <p>Call sequence in production:
 * <ol>
 *   <li>{@code GET /api/v1/orchestration/pipelines/backfill} → audit list (dry-run)</li>
 *   <li>Verify coverage = 100% (planned items count == modeling.data_model count)</li>
 *   <li>{@code POST /api/v1/orchestration/pipelines/backfill?dryRun=false} → execute</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines")
@RequiredArgsConstructor
@Slf4j
public class PipelineBackfillController {

    private final PipelineBackfillService backfillService;

    @GetMapping("/backfill")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<PipelineBackfillResult> backfillDryRun() {
        return ApiResponse.ok(backfillService.backfill(true));
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<PipelineBackfillResult> backfillExecute(@RequestParam(defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(backfillService.backfill(dryRun));
    }
}
