package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.security.InternalApiTokenFilter;
import com.onelake.orchestration.dto.TaskRunCallbackRequest;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
import com.onelake.orchestration.service.OrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 内部节点状态回调接口。
 *
 * <p>该接口仅供 Dagster/Spark 执行器通过内部令牌调用，不面向前端用户。
 */
@RestController
@RequestMapping("/api/v1/internal/orchestration")
@RequiredArgsConstructor
public class InternalTaskRunCallbackController {

    public static final String INTERNAL_TOKEN_HEADER = InternalApiTokenFilter.INTERNAL_TOKEN_HEADER;

    private final OrchestrationService orchestrationService;

    /**
     * 接收 Dagster 节点执行器的状态回调。
     *
     * <p>鉴权由 {@link InternalApiTokenFilter} 在进入 Controller 前完成，这里只处理
     * 已认证内部请求的参数校验与业务分发。
     */
    @PostMapping("/runs/{runId}/tasks/{taskKey}/status")
    public ApiResponse<TaskRunCallbackResult> applyTaskRunStatus(
            @PathVariable UUID runId,
            @PathVariable String taskKey,
            @Valid @RequestBody TaskRunCallbackRequest request) {
        return ApiResponse.ok(orchestrationService.applyTaskRunCallback(runId, taskKey, request));
    }
}
