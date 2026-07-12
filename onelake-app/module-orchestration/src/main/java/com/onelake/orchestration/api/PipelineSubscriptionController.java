package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineSubscriptionDTO;
import com.onelake.orchestration.dto.PipelineSubscriptionRequest;
import com.onelake.orchestration.service.PipelineSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 资产或上游流水线驱动的自动化订阅管理接口。 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines/{dagId}/subscriptions")
@RequiredArgsConstructor
@Tag(name = "流水线自动化", description = "资产与上游流水线驱动的声明式自动触发订阅。")
public class PipelineSubscriptionController {

    private final PipelineSubscriptionService subscriptionService;

    @Operation(summary = "查询自动化订阅")
    @GetMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<PipelineSubscriptionDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(subscriptionService.list(dagId));
    }

    @Operation(summary = "新增自动化订阅")
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineSubscriptionDTO> create(
            @PathVariable UUID dagId,
            @RequestBody PipelineSubscriptionRequest request) {
        return ApiResponse.ok(subscriptionService.create(dagId, request));
    }

    @Operation(summary = "删除自动化订阅")
    @DeleteMapping("/{subscriptionId}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID dagId,
                                    @PathVariable UUID subscriptionId) {
        subscriptionService.delete(dagId, subscriptionId);
        return ApiResponse.ok(null);
    }
}
