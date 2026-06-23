package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.service.OrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orchestration/dags")
@RequiredArgsConstructor
@Tag(name = "任务编排", description = "DAG 创建、查询、触发和运行历史接口。")
public class DagController {

    private final OrchestrationService service;

    @Operation(
        summary = "创建 DAG",
        description = "用途：保存编排画布或 SQL 工作台生成的 Dagster 作业定义。前端对接：OrchestrationAPI.createDag，由 SqlWorkbench 发布到编排和 DagCanvas 扩展流程使用。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DagDTO> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String dagsterJob = (String) body.get("dagsterJob");
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) body.get("definition");
        String cron = (String) body.get("scheduleCron");
        Boolean enabled = body.get("enabled") instanceof Boolean value ? value : null;
        return ApiResponse.ok(service.createDag(name, dagsterJob, definition, cron, enabled));
    }

    @Operation(
        summary = "更新 DAG 草稿",
        description = "用途：保存编排画布修改后的 DAG definition。前端对接：OrchestrationAPI.updateDag，由 DagCanvas 保存按钮调用。"
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DagDTO> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String dagsterJob = (String) body.get("dagsterJob");
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) body.get("definition");
        String cron = (String) body.get("scheduleCron");
        Boolean enabled = body.get("enabled") instanceof Boolean value ? value : null;
        return ApiResponse.ok(service.updateDag(id, name, dagsterJob, definition, cron, enabled));
    }

    @Operation(
        summary = "获取 DAG 详情",
        description = "用途：读取 DAG 定义、作业名和调度配置。前端对接：OrchestrationAPI.getDag，由 DagCanvas 详情页加载。"
    )
    @GetMapping("/{id}")
    public ApiResponse<DagDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getDag(id));
    }

    @Operation(
        summary = "查询 DAG 列表",
        description = "用途：返回当前租户下的编排 DAG。前端对接：OrchestrationAPI.listDags 已封装，当前页面未直接调用。"
    )
    @GetMapping
    public ApiResponse<List<DagDTO>> list() {
        return ApiResponse.ok(service.listDags());
    }

    @Operation(
        summary = "触发 DAG 运行",
        description = "用途：按触发类型启动一次 Dagster 作业运行。前端对接：OrchestrationAPI.triggerDag 已封装，当前页面未直接调用。"
    )
    @PostMapping("/{id}/run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, Object>> run(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "MANUAL") String trigger) {
        UUID runId = service.triggerDag(id, TriggerType.valueOf(trigger.toUpperCase()));
        return ApiResponse.ok(Map.of("runId", runId));
    }

    @Operation(
        summary = "分页查询 DAG 运行历史",
        description = "用途：返回 DAG 运行实例列表。前端对接：当前 OrchestrationAPI 未封装，供后续 RunInstances 页面接入。"
    )
    @GetMapping("/{id}/runs")
    public ApiResponse<Page<JobRunDTO>> runs(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.runs(id, PageRequest.of(page, size)));
    }
}
