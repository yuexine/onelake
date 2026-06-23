package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.service.OrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orchestration/runs")
@RequiredArgsConstructor
@Tag(name = "任务编排运行实例", description = "租户范围内的 DAG 运行历史查询接口。")
public class JobRunController {

    private final OrchestrationService service;

    @Operation(
        summary = "分页查询当前租户运行实例",
        description = "用途：返回当前租户下全部 DAG 的运行实例。前端对接：OrchestrationAPI.listRuns，由 RunInstances 页面加载。"
    )
    @GetMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Page<JobRunDTO>> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(service.listRuns(PageRequest.of(pageNumber, pageSize)));
    }
}
