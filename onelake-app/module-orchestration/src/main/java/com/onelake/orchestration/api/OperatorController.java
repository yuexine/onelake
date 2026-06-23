package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.OperatorDTO;
import com.onelake.orchestration.dto.OperatorInstallRequest;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import com.onelake.orchestration.dto.OperatorValidationResultDTO;
import com.onelake.orchestration.dto.OperatorVersionRequest;
import com.onelake.orchestration.dto.UpdateOperatorRequest;
import com.onelake.orchestration.service.OperatorService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orchestration/operators")
@RequiredArgsConstructor
@Tag(name = "算子市场", description = "统一算子 Manifest、版本、市场浏览与安装接口。")
public class OperatorController {

    private final OperatorService service;

    @Operation(
        summary = "查询算子市场",
        description = "用途：返回当前租户可见的内置、自定义和已安装租户私有算子。前端对接：OperatorAPI.listOperators。"
    )
    @GetMapping
    public ApiResponse<List<OperatorDTO>> list(@RequestParam(required = false) String category,
                                               @RequestParam(required = false) String scope,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listOperators(category, scope, keyword));
    }

    @Operation(
        summary = "获取算子详情",
        description = "用途：返回算子当前 Manifest 和版本列表，供市场详情与画布属性面板使用。"
    )
    @GetMapping("/{ref:.+}")
    public ApiResponse<OperatorDTO> get(@PathVariable String ref) {
        return ApiResponse.ok(service.getOperator(ref));
    }

    @Operation(
        summary = "校验算子 Manifest",
        description = "用途：注册或发布自定义算子前做 Manifest 自校验，不落库。"
    )
    @PostMapping("/validate")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorValidationResultDTO> validate(@RequestBody OperatorManifestDTO manifest) {
        return ApiResponse.ok(service.validateOperator(manifest));
    }

    @Operation(
        summary = "校验算子图",
        description = "用途：按市场 Manifest 校验 DAG/OperatorGraph 的节点、边、版本、必需参数和 SQL_DBT 编译目标，不落库。"
    )
    @PostMapping("/graph/validate")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorValidationResultDTO> validateGraph(@RequestBody Map<String, Object> graph) {
        return ApiResponse.ok(service.validateGraph(graph));
    }

    @Operation(
        summary = "注册自定义算子",
        description = "用途：以统一 Manifest 契约注册 CUSTOM 或 TENANT_PRIVATE 算子。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorDTO> register(@RequestBody OperatorManifestDTO manifest) {
        return ApiResponse.ok(service.registerOperator(manifest));
    }

    @Operation(
        summary = "发布算子新版本",
        description = "用途：为当前租户维护的自定义/私有算子追加 Manifest 版本快照。"
    )
    @PostMapping("/{ref:.+}/versions")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorDTO> publishVersion(@PathVariable String ref,
                                                   @RequestBody OperatorVersionRequest request) {
        return ApiResponse.ok(service.publishVersion(ref, request));
    }

    @Operation(
        summary = "更新算子元信息",
        description = "用途：更新当前租户自定义/私有算子的名称、说明或弃用状态。"
    )
    @PutMapping("/{ref:.+}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorDTO> update(@PathVariable String ref,
                                           @RequestBody UpdateOperatorRequest request) {
        return ApiResponse.ok(service.updateOperator(ref, request));
    }

    @Operation(
        summary = "安装算子",
        description = "用途：为租户记录算子安装或版本锁定。内置算子默认可见，此接口用于显式锁定版本。"
    )
    @PostMapping("/{ref:.+}/install")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OperatorDTO> install(@PathVariable String ref,
                                            @RequestBody(required = false) OperatorInstallRequest request) {
        return ApiResponse.ok(service.installOperator(ref, request));
    }
}
