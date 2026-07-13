package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineNodeNotificationRequest;
import com.onelake.orchestration.dto.PipelineNodeNotificationResult;
import com.onelake.orchestration.dto.SubPipelineRunResult;
import com.onelake.orchestration.dto.SubPipelineTriggerRequest;
import com.onelake.orchestration.service.PipelineNodeExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal-token-protected backend adapters for graph-only control/observe nodes. */
@RestController
@RequestMapping("/api/v1/internal/orchestration/dagster")
@RequiredArgsConstructor
public class InternalPipelineNodeController {

    private final PipelineNodeExecutionService pipelineNodeExecutionService;

    @PostMapping("/sub-pipelines/trigger")
    public ApiResponse<SubPipelineRunResult> triggerSubPipeline(
            @Valid @RequestBody SubPipelineTriggerRequest request) {
        return ApiResponse.ok(pipelineNodeExecutionService.triggerSubPipeline(request));
    }

    @GetMapping("/sub-pipelines/runs/{childRunId}")
    public ApiResponse<SubPipelineRunResult> subPipelineStatus(
            @PathVariable UUID childRunId,
            @RequestParam UUID parentRunId) {
        return ApiResponse.ok(
                pipelineNodeExecutionService.subPipelineStatus(parentRunId, childRunId));
    }

    @PostMapping("/notifications")
    public ApiResponse<PipelineNodeNotificationResult> notify(
            @Valid @RequestBody PipelineNodeNotificationRequest request) {
        return ApiResponse.ok(pipelineNodeExecutionService.notify(request));
    }
}
