package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.dto.SyncRunLogDTO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.dto.SyncTaskDryRunDTO;
import com.onelake.integration.service.SyncTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integration/sync-tasks")
@RequiredArgsConstructor
@Tag(name = "采集任务", description = "批/流采集任务的创建、运行、诊断和运行历史接口。")
public class SyncTaskController {

    private final SyncTaskService service;

    @Operation(
        summary = "创建采集任务",
        description = "用途：保存源表到目标表的采集任务定义。前端对接：IntegrationAPI.createSyncTask，由 SyncTaskWizard 提交流程调用。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> create(@Valid @RequestBody CreateSyncTaskVO vo) {
        return ApiResponse.ok(service.create(vo));
    }

    @Operation(
        summary = "获取采集任务详情",
        description = "用途：读取任务配置、状态、源端和目标端信息。前端对接：IntegrationAPI.getSyncTask，由 SyncTaskDetail 和 FailureDiagnose 使用。"
    )
    @GetMapping("/{id}")
    public ApiResponse<SyncTaskDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(
        summary = "查询采集任务列表",
        description = "用途：按数据源、模式、状态和关键字筛选任务。前端对接：IntegrationAPI.listSyncTasks，由 SyncTaskList 使用。"
    )
    @GetMapping
    public ApiResponse<List<SyncTaskDTO>> list(@RequestParam(required = false) UUID sourceId,
                                               @RequestParam(required = false) String mode,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(sourceId, mode, status, keyword));
    }

    @Operation(
        summary = "按数据源查询采集任务",
        description = "用途：查看某个连接下已创建的采集任务。前端对接：IntegrationAPI.listSyncTasksBySource，由 DatasourceDetail 使用。"
    )
    @GetMapping("/by-source/{sourceId}")
    public ApiResponse<List<SyncTaskDTO>> listBySource(@PathVariable UUID sourceId) {
        return ApiResponse.ok(service.listBySource(sourceId));
    }

    @Operation(
        summary = "更新采集任务",
        description = "用途：修改已有任务配置。前端对接：IntegrationAPI.updateSyncTask 已封装，当前页面未直接调用，供后续任务编辑页使用。"
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> update(@PathVariable UUID id,
                                           @RequestBody UpdateSyncTaskVO vo) {
        return ApiResponse.ok(service.update(id, vo));
    }

    @Operation(
        summary = "删除采集任务",
        description = "用途：移除未再使用的采集任务。前端对接：IntegrationAPI.deleteSyncTask，由 SyncTaskList 删除操作调用。"
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @Operation(
        summary = "启用采集任务",
        description = "用途：将任务切换为可调度或可运行状态。前端对接：IntegrationAPI.enableSyncTask，由 SyncTaskList、SyncTaskDetail 和 SyncTaskWizard 发布后调用。"
    )
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> enable(@PathVariable UUID id) {
        return ApiResponse.ok(service.enable(id));
    }

    @Operation(
        summary = "停用采集任务",
        description = "用途：暂停任务调度或手动运行入口。前端对接：IntegrationAPI.disableSyncTask，由 SyncTaskList 和 SyncTaskDetail 调用。"
    )
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> disable(@PathVariable UUID id) {
        return ApiResponse.ok(service.disable(id));
    }

    @Operation(
        summary = "试运行采集任务草稿",
        description = "用途：在保存前校验源表、目标表、权限和调度准备状态。前端对接：IntegrationAPI.dryRunSyncTaskDraft，由 SyncTaskWizard 预检查调用。"
    )
    @PostMapping("/dry-run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDryRunDTO> dryRunDraft(@Valid @RequestBody CreateSyncTaskVO vo) {
        return ApiResponse.ok(service.dryRun(vo));
    }

    @Operation(
        summary = "试运行已保存采集任务",
        description = "用途：对已保存任务执行运行前检查。前端对接：IntegrationAPI.dryRunSyncTask，由 SyncTaskDetail 调用。"
    )
    @PostMapping("/{id}/dry-run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDryRunDTO> dryRun(@PathVariable UUID id) {
        return ApiResponse.ok(service.dryRun(id));
    }

    @Operation(
        summary = "运行采集任务",
        description = "用途：触发一次采集运行并返回 runId。前端对接：当前前端使用 /trigger，本接口保留给兼容或直接运行调用。"
    )
    @PostMapping("/{id}/run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<java.util.Map<String, Object>> run(@PathVariable UUID id) {
        UUID runId = service.trigger(id);
        return ApiResponse.ok(java.util.Map.of("runId", runId));
    }

    @Operation(
        summary = "触发采集任务",
        description = "用途：手动触发任务执行并返回 runId。前端对接：IntegrationAPI.triggerSyncTask，由 SyncTaskList 和 SyncTaskDetail 调用。"
    )
    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<java.util.Map<String, Object>> trigger(@PathVariable UUID id) {
        UUID runId = service.trigger(id);
        return ApiResponse.ok(java.util.Map.of("runId", runId));
    }

    @Operation(
        summary = "对齐采集运行状态",
        description = "用途：由运维手动从外部执行引擎回查并修正运行状态。前端对接：当前 API 聚合层未封装，供运维诊断或后台任务使用。"
    )
    @PostMapping("/runs/{runId}/reconcile")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<Void> reconcile(@PathVariable UUID runId) {
        service.reconcile(runId);
        return ApiResponse.ok();
    }

    @Operation(
        summary = "获取采集运行详情",
        description = "用途：读取单次运行状态、耗时、检查点和错误信息。前端对接：IntegrationAPI.getSyncRun，由 FailureDiagnose 使用。"
    )
    @GetMapping("/runs/{runId}")
    public ApiResponse<SyncRunDTO> getRun(@PathVariable UUID runId) {
        return ApiResponse.ok(service.getRun(runId));
    }

    @Operation(
        summary = "获取采集运行日志",
        description = "用途：读取外部执行引擎或内部记录的运行日志。前端对接：IntegrationAPI.getSyncRunLogs，由 SyncTaskDetail 日志抽屉使用。"
    )
    @GetMapping("/runs/{runId}/logs")
    public ApiResponse<List<SyncRunLogDTO>> logs(@PathVariable UUID runId) {
        return ApiResponse.ok(service.logs(runId));
    }

    @Operation(
        summary = "取消采集运行",
        description = "用途：终止运行中的采集实例。前端对接：IntegrationAPI.cancelSyncRun，由 SyncTaskDetail 和全局 TaskAPI.cancel 通过 cancelEndpoint 调用。"
    )
    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncRunDTO> cancel(@PathVariable UUID runId) {
        return ApiResponse.ok(service.cancelRun(runId));
    }

    @Operation(
        summary = "分页查询任务运行历史",
        description = "用途：返回某采集任务的运行实例列表。前端对接：IntegrationAPI.listRuns，由 SyncTaskDetail 历史表格使用。"
    )
    @GetMapping("/{id}/runs")
    public ApiResponse<Page<SyncRunDTO>> runs(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(service.runs(id, pageable));
    }
}
