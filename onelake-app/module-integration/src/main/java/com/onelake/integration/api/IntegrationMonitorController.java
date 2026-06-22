package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.integration.domain.entity.SyncRun;
import com.onelake.integration.domain.enums.RunStatus;
import com.onelake.integration.repository.SyncRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 采集监控 API（对应前端 CollectMonitor 页面）。
 * 聚合 sync_run 数据，提供健康汇总 / 吞吐时序 / 失败 Top。
 */
@RestController
@RequestMapping("/api/v1/integration/monitor")
@RequiredArgsConstructor
@Tag(name = "采集监控", description = "基于 sync_run 聚合采集健康、失败排行等监控数据。")
public class IntegrationMonitorController {

    private final SyncRunRepository runRepo;

    /** 健康汇总：成功率 / 运行中 / 失败 / 平均时延。 */
    @Operation(
        summary = "查询采集健康汇总",
        description = "用途：聚合最近 N 小时采集总数、成功率、失败数、运行中数和平均耗时。前端对接：IntegrationAPI.healthSummary，由 CollectMonitor 页面使用。"
    )
    @GetMapping("/health-summary")
    public ApiResponse<Map<String, Object>> healthSummary(
            @RequestParam(defaultValue = "24") int hours) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");

        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<SyncRun> recent = runRepo.findAll().stream()
            .filter(r -> r.getStartedAt() != null && r.getStartedAt().isAfter(since))
            .toList();

        long total = recent.size();
        long succeeded = recent.stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED).count();
        long failed = recent.stream().filter(r -> r.getStatus() == RunStatus.FAILED).count();
        long running = recent.stream().filter(r -> r.getStatus() == RunStatus.RUNNING).count();
        double avgDuration = recent.stream()
            .filter(r -> r.getStatus() == RunStatus.SUCCEEDED && r.getFinishedAt() != null)
            .mapToLong(r -> java.time.Duration.between(r.getStartedAt(), r.getFinishedAt()).toMillis())
            .average().orElse(0);

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("succeeded", succeeded);
        summary.put("failed", failed);
        summary.put("running", running);
        summary.put("successRate", total > 0 ? Math.round(succeeded * 10000.0 / total) / 100.0 : 100.0);
        summary.put("avgDurationMs", (long) avgDuration);
        summary.put("windowHours", hours);
        return ApiResponse.ok(summary);
    }

    /** 失败 Top：按 taskId 聚合最近 N 小时的失败次数。 */
    @Operation(
        summary = "查询失败任务排行",
        description = "用途：按任务聚合最近 N 小时失败次数。前端对接：IntegrationAPI.failTop，由 CollectMonitor 页面展示失败 Top 列表。"
    )
    @GetMapping("/fail-top")
    public ApiResponse<List<Map<String, Object>>> failTop(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");

        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        runRepo.findAll().stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED
                && r.getStartedAt() != null && r.getStartedAt().isAfter(since))
            .forEach(r -> counts.merge(r.getTaskId().toString(), 1L, Long::sum));

        List<Map<String, Object>> top = counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(e -> {
                Map<String, Object> row = new HashMap<>();
                row.put("taskId", e.getKey());
                row.put("failCount", e.getValue());
                return row;
            })
            .toList();
        return ApiResponse.ok(top);
    }
}
