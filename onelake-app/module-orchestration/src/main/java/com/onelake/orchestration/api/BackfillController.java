package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.CreateBackfillRequest;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.service.BackfillDispatcher;
import com.onelake.orchestration.service.BackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 业务日期区间回填 API。
 */
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Tag(name = "任务编排回填", description = "租户范围内的流水线业务日期回填创建、进度查询与取消接口。")
public class BackfillController {

    private final BackfillService backfillService;
    private final BackfillDispatcher backfillDispatcher;

    @Operation(
        summary = "创建并启动流水线回填",
        description = "用途：按业务日期区间创建回填批次，并异步按 maxParallel 派发首批子运行。"
    )
    @PostMapping("/pipelines/{dagId}/backfill")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<BackfillDTO> create(@PathVariable UUID dagId,
                                           @Valid @RequestBody CreateBackfillRequest request) {
        BackfillDTO created = backfillService.createBackfill(
                dagId,
                request.rangeStart(),
                request.rangeEnd(),
                request.grain(),
                request.maxParallel());
        backfillDispatcher.dispatchNow(created.id());
        return ApiResponse.ok(created);
    }

    @Operation(
        summary = "获取回填进度详情",
        description = "用途：按 backfillId 返回批次状态、成功/失败计数、日期区间和子运行明细。"
    )
    @GetMapping("/backfills/{id}")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<BackfillDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(backfillService.getBackfill(id));
    }

    @Operation(
        summary = "查询流水线回填批次列表",
        description = "用途：返回指定流水线在当前租户下的回填批次，按创建时间倒序排列。"
    )
    @GetMapping("/pipelines/{dagId}/backfills")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<List<BackfillDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(backfillService.listBackfills(dagId));
    }

    @Operation(
        summary = "分页查询回填子运行",
        description = "用途：按 backfillId 分页返回真实 JobRun 元数据，供进度页和运行实例页展示。"
    )
    @GetMapping("/backfills/{id}/runs")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<Page<JobRunDTO>> listRuns(@PathVariable UUID id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(backfillService.listJobRuns(id, PageRequest.of(pageNumber, pageSize)));
    }

    @Operation(
        summary = "获取回填子运行详情",
        description = "用途：校验回填批次归属后，按 runId 返回真实 JobRun 元数据。"
    )
    @GetMapping("/backfills/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<JobRunDTO> getRun(@PathVariable UUID id, @PathVariable UUID runId) {
        return ApiResponse.ok(backfillService.getJobRun(id, runId));
    }

    @Operation(
        summary = "取消回填批次",
        description = "用途：停止继续派发回填子运行，并取消仍在运行中的子 JobRun。"
    )
    @PostMapping("/backfills/{id}/cancel")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<BackfillDTO> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(backfillService.cancelBackfill(id));
    }
}
