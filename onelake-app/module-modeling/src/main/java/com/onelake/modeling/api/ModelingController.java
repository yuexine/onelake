package com.onelake.modeling.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.exception.BizException;
import com.onelake.modeling.domain.entity.Metric;
import com.onelake.modeling.domain.entity.SubjectDomain;
import com.onelake.modeling.dto.DwdModelCompileDTO;
import com.onelake.modeling.dto.DataModelDTO;
import com.onelake.modeling.dto.DwdModelDraftRequest;
import com.onelake.modeling.dto.DwdModelRunDTO;
import com.onelake.modeling.dto.DwdModelRunRequest;
import com.onelake.modeling.dto.DwdModelValidationDTO;
import com.onelake.modeling.service.DwdModelService;
import com.onelake.modeling.service.ModelingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/modeling")
@RequiredArgsConstructor
@Tag(name = "数据建模", description = "主题域、指标和 DWD 模型草稿、校验、编译、运行接口。")
public class ModelingController {

    private final ModelingService service;
    private final DwdModelService dwdModelService;

    @Operation(
        summary = "创建主题域",
        description = "用途：维护业务主题域层级。前端对接：当前 ModelingAPI 未封装创建入口，供后续建模管理页使用。"
    )
    @PostMapping("/domains")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SubjectDomain> createDomain(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createDomain(
            (String) body.get("code"),
            (String) body.get("name"),
            body.get("parentId") == null ? null : UUID.fromString(body.get("parentId").toString())));
    }

    @Operation(
        summary = "查询主题域列表",
        description = "用途：返回建模主题域树或列表。前端对接：ModelingAPI.listDomains 已封装，当前页面尚未直接使用。"
    )
    @GetMapping("/domains")
    public ApiResponse<List<SubjectDomain>> listDomains() {
        return ApiResponse.ok(service.listDomains());
    }

    @Operation(
        summary = "创建指标",
        description = "用途：在主题域下登记指标定义。前端对接：当前 ModelingAPI 未封装创建入口，供后续指标管理页使用。"
    )
    @PostMapping("/metrics")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Metric> createMetric(@RequestBody Metric body) {
        if (body.getCode() == null || body.getName() == null || body.getMetricType() == null) {
            throw new BizException(40001, "code/name/metricType 必填");
        }
        return ApiResponse.ok(service.createMetric(body));
    }

    @Operation(
        summary = "获取指标详情",
        description = "用途：读取单个指标定义。前端对接：当前 ModelingAPI 未封装详情调用，供后续指标详情页使用。"
    )
    @GetMapping("/metrics/{id}")
    public ApiResponse<Metric> getMetric(@PathVariable UUID id) {
        return ApiResponse.ok(service.getMetric(id));
    }

    @Operation(
        summary = "按主题域查询指标",
        description = "用途：返回某主题域下的指标列表。前端对接：ModelingAPI.listMetricsByDomain 已封装，当前页面尚未直接使用。"
    )
    @GetMapping("/metrics/by-domain/{domainId}")
    public ApiResponse<List<Metric>> listByDomain(@PathVariable UUID domainId) {
        return ApiResponse.ok(service.listMetricsByDomain(domainId));
    }

    @Operation(
        summary = "创建 DWD 模型草稿",
        description = "用途：基于源表和字段映射创建 DWD 模型。前端对接：ModelingAPI.createDwdDraft，由 TableWizard 的 DWD 建模流程调用。"
    )
    @PostMapping("/models/dwd/draft")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataModelDTO> createDwdDraft(@RequestBody DwdModelDraftRequest request) {
        return ApiResponse.ok(dwdModelService.createDraft(request));
    }

    @Operation(
        summary = "查询数据模型列表",
        description = "用途：按源表或目标表查询模型。前端对接：ModelingAPI.listModels，由 TableDetail 查找与当前资产相关模型。"
    )
    @GetMapping("/models")
    public ApiResponse<List<DataModelDTO>> listModels(@RequestParam(required = false) String sourceFqn,
                                                      @RequestParam(required = false) String targetFqn) {
        return ApiResponse.ok(dwdModelService.list(sourceFqn, targetFqn));
    }

    @Operation(
        summary = "获取数据模型详情",
        description = "用途：读取模型定义、字段映射和编译状态。前端对接：ModelingAPI.getModel 已封装，当前页面未直接调用。"
    )
    @GetMapping("/models/{id}")
    public ApiResponse<DataModelDTO> getModel(@PathVariable UUID id) {
        return ApiResponse.ok(dwdModelService.get(id));
    }

    @Operation(
        summary = "更新数据模型",
        description = "用途：修改模型草稿定义和字段映射。前端对接：ModelingAPI.updateModel 已封装，当前页面未直接调用。"
    )
    @PutMapping("/models/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataModelDTO> updateModel(@PathVariable UUID id,
                                                 @RequestBody DwdModelDraftRequest request) {
        return ApiResponse.ok(dwdModelService.update(id, request));
    }

    @Operation(
        summary = "校验数据模型",
        description = "用途：检查模型 SQL、字段映射和目标表可用性。前端对接：ModelingAPI.validateModel 已封装，当前页面未直接调用。"
    )
    @PostMapping("/models/{id}/validate")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DwdModelValidationDTO> validateModel(@PathVariable UUID id) {
        return ApiResponse.ok(dwdModelService.validate(id));
    }

    @Operation(
        summary = "编译数据模型产物",
        description = "用途：生成 dbt/Dagster 等模型运行产物。前端对接：ModelingAPI.compileModel，由 TableDetail 模型动作调用。"
    )
    @PostMapping("/models/{id}/compile")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DwdModelCompileDTO> compileModel(@PathVariable UUID id) {
        return ApiResponse.ok(dwdModelService.compileArtifacts(id));
    }

    @Operation(
        summary = "运行数据模型",
        description = "用途：触发 DWD 模型运行并记录运行实例。前端对接：ModelingAPI.runModel，由 TableDetail 模型动作调用。"
    )
    @PostMapping("/models/{id}/run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DwdModelRunDTO> runModel(@PathVariable UUID id,
                                                @RequestBody(required = false) DwdModelRunRequest request) {
        return ApiResponse.ok(dwdModelService.run(id, request));
    }

    @Operation(
        summary = "查询模型运行历史",
        description = "用途：返回指定模型的运行实例列表。前端对接：ModelingAPI.listModelRuns，由 TableDetail 加载模型运行历史。"
    )
    @GetMapping("/models/{id}/runs")
    public ApiResponse<List<DwdModelRunDTO>> modelRuns(@PathVariable UUID id) {
        return ApiResponse.ok(dwdModelService.runs(id));
    }

    @Operation(
        summary = "获取模型运行详情",
        description = "用途：读取单次模型运行状态、日志和产物信息。前端对接：ModelingAPI.getModelRun，由 TableDetail 轮询运行状态。"
    )
    @GetMapping("/model-runs/{runId}")
    public ApiResponse<DwdModelRunDTO> modelRun(@PathVariable UUID runId) {
        return ApiResponse.ok(dwdModelService.getRun(runId));
    }
}
