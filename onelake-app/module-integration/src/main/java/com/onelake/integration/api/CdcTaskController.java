package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.domain.entity.CdcTask;
import com.onelake.integration.dto.CdcStatusDTO;
import com.onelake.integration.service.CdcTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integration/cdc-tasks")
@RequiredArgsConstructor
@Tag(name = "CDC 任务", description = "CDC 增量采集任务的创建、启停和状态查询接口。")
public class CdcTaskController {

    private final CdcTaskService service;

    @Operation(
        summary = "创建 CDC 任务",
        description = "用途：为指定数据源和表创建 CDC 监听任务。前端对接：当前 IntegrationAPI 未封装创建入口，CdcMonitor 目前只读取和启停已有任务。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.create(UUID.fromString(body.get("sourceId")), body.get("tableName")));
    }

    @Operation(
        summary = "查询 CDC 任务列表",
        description = "用途：返回租户下 CDC 任务清单。前端对接：IntegrationAPI.listCdcTasks，由 CdcMonitor 进入页面时加载首个任务。"
    )
    @GetMapping
    public ApiResponse<List<CdcTask>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
        summary = "获取 CDC 任务详情",
        description = "用途：读取单个 CDC 任务配置与状态。前端对接：当前 API 聚合层未封装详情调用，供后续 CDC 详情页使用。"
    )
    @GetMapping("/{id}")
    public ApiResponse<CdcTask> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(
        summary = "删除 CDC 任务",
        description = "用途：删除不再监听的 CDC 任务。前端对接：当前 API 聚合层未封装删除入口，供后续 CDC 管理页使用。"
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @Operation(
        summary = "启动 CDC 任务",
        description = "用途：启动指定 CDC 任务的增量采集。前端对接：IntegrationAPI.startCdcTask 已封装，当前 CdcMonitor 可用于启停扩展。"
    )
    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> start(@PathVariable UUID id) {
        return ApiResponse.ok(service.start(id));
    }

    @Operation(
        summary = "停止 CDC 任务",
        description = "用途：暂停指定 CDC 任务的增量采集。前端对接：IntegrationAPI.stopCdcTask 已封装，当前 CdcMonitor 可用于启停扩展。"
    )
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> stop(@PathVariable UUID id) {
        return ApiResponse.ok(service.pause(id));
    }

    @Operation(
        summary = "查询 CDC 运行状态",
        description = "用途：返回位点、延迟、事件计数等 CDC 状态。前端对接：IntegrationAPI.getCdcStatus，由 CdcMonitor 加载任务后调用。"
    )
    @GetMapping("/{id}/status")
    public ApiResponse<CdcStatusDTO> status(@PathVariable UUID id) {
        return ApiResponse.ok(service.status(id));
    }
}
