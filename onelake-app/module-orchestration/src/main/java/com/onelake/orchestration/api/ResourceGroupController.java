package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.ComputeProfileDTO;
import com.onelake.orchestration.dto.ComputeProfileRequest;
import com.onelake.orchestration.dto.ResourceGroupDTO;
import com.onelake.orchestration.dto.ResourceGroupRequest;
import com.onelake.orchestration.service.ResourceGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 编排资源组与计算画像接口。
 */
@RestController
@RequestMapping("/api/v1/orchestration/resource-groups")
@RequiredArgsConstructor
@Tag(name = "编排资源组", description = "资源组与计算画像注册表，供流水线和算子资源契约校验复用。")
public class ResourceGroupController {

    private final ResourceGroupService service;

    @Operation(
        summary = "查询资源组注册表",
        description = "用途：返回当前租户可见的内置和租户自定义资源组及其计算画像。前端对接：OperatorAPI.listResourceGroups。"
    )
    @GetMapping
    public ApiResponse<List<ResourceGroupDTO>> list() {
        return ApiResponse.ok(service.listResourceGroups());
    }

    @Operation(
        summary = "注册或更新租户资源组",
        description = "用途：维护流水线可选择的 resourceGroup、engine、配额和成本策略；不会改写内置资源组。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ResourceGroupDTO> upsert(@RequestBody ResourceGroupRequest request) {
        return ApiResponse.ok(service.upsertResourceGroup(request));
    }

    @Operation(
        summary = "更新租户资源组",
        description = "用途：按路径 code 更新租户自定义资源组，便于前端表格保存。"
    )
    @PutMapping("/{code}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ResourceGroupDTO> update(@PathVariable String code, @RequestBody ResourceGroupRequest request) {
        ResourceGroupRequest normalized = new ResourceGroupRequest(
            code,
            request.displayName(),
            request.engine(),
            request.status(),
            request.maxConcurrency(),
            request.quotaCpu(),
            request.quotaMemoryGb(),
            request.costPolicy()
        );
        return ApiResponse.ok(service.upsertResourceGroup(normalized));
    }

    @Operation(
        summary = "注册或更新计算画像",
        description = "用途：维护指定资源组下的 computeProfile，供 operator graph 的资源契约校验使用。"
    )
    @PostMapping("/{groupCode}/profiles")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ComputeProfileDTO> upsertProfile(@PathVariable String groupCode,
                                                        @RequestBody ComputeProfileRequest request) {
        return ApiResponse.ok(service.upsertComputeProfile(groupCode, request));
    }

    @Operation(
        summary = "更新计算画像",
        description = "用途：按路径 code 更新租户自定义计算画像，便于前端表格保存。"
    )
    @PutMapping("/{groupCode}/profiles/{profileCode}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ComputeProfileDTO> updateProfile(@PathVariable String groupCode,
                                                        @PathVariable String profileCode,
                                                        @RequestBody ComputeProfileRequest request) {
        ComputeProfileRequest normalized = new ComputeProfileRequest(
            profileCode,
            request.displayName(),
            request.engine(),
            request.status(),
            request.cpuCores(),
            request.memoryGb(),
            request.maxScanBytes(),
            request.timeoutSeconds()
        );
        return ApiResponse.ok(service.upsertComputeProfile(groupCode, normalized));
    }
}
