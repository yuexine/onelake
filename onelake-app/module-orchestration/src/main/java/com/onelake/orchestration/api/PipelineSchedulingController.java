package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.DagSchedulingDTO;
import com.onelake.orchestration.dto.ScheduleCalendarDTO;
import com.onelake.orchestration.dto.UpdateDagSchedulingRequest;
import com.onelake.orchestration.service.PipelineSchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 统一流水线编辑器使用的生产调度配置与调度日历接口。 */
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Tag(name = "流水线调度配置", description = "调度增强字段读写与租户日历选择接口。")
public class PipelineSchedulingController {

    private final PipelineSchedulingService schedulingService;

    @Operation(summary = "读取流水线调度配置")
    @GetMapping("/pipelines/{dagId}/scheduling")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DagSchedulingDTO> get(@PathVariable UUID dagId) {
        return ApiResponse.ok(schedulingService.getScheduling(dagId));
    }

    @Operation(summary = "更新流水线调度配置")
    @PutMapping("/pipelines/{dagId}/scheduling")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DagSchedulingDTO> update(@PathVariable UUID dagId,
                                                @RequestBody UpdateDagSchedulingRequest request) {
        return ApiResponse.ok(schedulingService.updateScheduling(dagId, request));
    }

    @Operation(summary = "列出可绑定的租户调度日历")
    @GetMapping("/schedule-calendars")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<ScheduleCalendarDTO>> calendars() {
        return ApiResponse.ok(schedulingService.listCalendars());
    }
}
