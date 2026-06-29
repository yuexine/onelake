package com.onelake.analytics.api;

import com.onelake.analytics.api.vo.DashboardPublishRequest;
import com.onelake.analytics.api.vo.DashboardSaveRequest;
import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.entity.DashboardPublication;
import com.onelake.analytics.service.DashboardService;
import com.onelake.analytics.service.SharePublishService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.util.JsonUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 大屏 Controller。
 * 路径前缀：/api/v1/analytics/dashboards
 *
 * 发布/分享：/publish 子资源；公开通道：/api/v1/analytics/share/screen/{token}。
 */
@RestController
@RequestMapping("/api/v1/analytics/dashboards")
@RequiredArgsConstructor
@Tag(name = "数据分析-大屏", description = "自研大屏编排：CRUD + 保存草稿 + 发布 + 分享")
public class AnalyticsDashboardController {

    private final DashboardService dashboardService;
    private final SharePublishService publishService;

    @Operation(summary = "新建大屏")
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Dashboard> create(@RequestParam String name,
                                         @RequestParam(required = false) String description) {
        return ApiResponse.ok(dashboardService.create(name, description));
    }

    @Operation(summary = "保存大屏草稿")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Dashboard> save(@PathVariable UUID id, @RequestBody DashboardSaveRequest req) {
        return ApiResponse.ok(dashboardService.save(id, req));
    }

    @Operation(summary = "获取大屏详情")
    @GetMapping("/{id}")
    public ApiResponse<Dashboard> get(@PathVariable UUID id) {
        return ApiResponse.ok(dashboardService.get(id));
    }

    @Operation(summary = "列出当前租户的大屏")
    @GetMapping
    public ApiResponse<List<Dashboard>> list() {
        return ApiResponse.ok(dashboardService.list());
    }

    @Operation(summary = "删除大屏")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        dashboardService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "发布大屏（生成快照 + 可选公开分享）")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DashboardPublication> publish(@PathVariable UUID id,
                                                     @RequestBody DashboardPublishRequest req) {
        return ApiResponse.ok(publishService.publish(id, req));
    }

    @Operation(summary = "拉取当前线上版（已发布快照）")
    @GetMapping("/{id}/publication")
    public ApiResponse<Map<String, Object>> currentPublication(@PathVariable UUID id) {
        Dashboard d = dashboardService.get(id);
        return ApiResponse.ok(Map.of(
            "version", d.getVersion(),
            "current_publication_id", String.valueOf(d.getCurrentPublicationId()),
            "canvas", JsonUtil.parse(d.getCanvas()),
            "spec", JsonUtil.parse(d.getSpec())
        ));
    }
}
