package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.ParamDTO;
import com.onelake.orchestration.dto.ParamReplaceRequest;
import com.onelake.orchestration.service.PipelineParamService;
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

/** 流水线三级参数管理接口。 */
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Tag(name = "流水线参数", description = "租户全局、流水线和节点三级参数管理。")
public class PipelineParamController {

    private final PipelineParamService paramService;

    @Operation(summary = "查询流水线参数", description = "返回指定流水线的 PIPELINE 与 TASK 两级参数。")
    @GetMapping("/pipelines/{dagId}/params")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<ParamDTO>> listPipelineParams(@PathVariable UUID dagId) {
        return ApiResponse.ok(paramService.listPipelineParams(dagId));
    }

    @Operation(summary = "保存流水线参数", description = "按请求 scope 定向替换 PIPELINE 或单个 TASK 参数集合，空 params 数组表示清空目标作用域。")
    @PutMapping("/pipelines/{dagId}/params")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<ParamDTO>> replacePipelineParams(@PathVariable UUID dagId,
                                                              @RequestBody ParamReplaceRequest request) {
        return ApiResponse.ok(paramService.replacePipelineParams(dagId, request));
    }

    @Operation(summary = "查询租户全局参数", description = "返回当前租户的 GLOBAL 参数。")
    @GetMapping("/params/global")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<ParamDTO>> listGlobalParams() {
        return ApiResponse.ok(paramService.listGlobalParams());
    }

    @Operation(summary = "保存租户全局参数", description = "用请求列表整体替换当前租户的 GLOBAL 参数，空数组表示清空。")
    @PutMapping("/params/global")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<ParamDTO>> replaceGlobalParams(@RequestBody List<ParamDTO> params) {
        return ApiResponse.ok(paramService.replaceGlobalParams(params));
    }
}
