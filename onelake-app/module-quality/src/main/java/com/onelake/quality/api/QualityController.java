package com.onelake.quality.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.quality.api.vo.CreateQualityRuleVO;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.dto.QualityAlertDTO;
import com.onelake.quality.dto.QualityRuleDTO;
import com.onelake.quality.dto.QualityRunResultDTO;
import com.onelake.quality.service.QualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
@Tag(name = "数据质量", description = "质量规则、稽核结果和质量告警接口。")
public class QualityController {

    private final QualityService service;

    @Operation(
        summary = "创建质量规则",
        description = "用途：为资产或字段配置质量校验规则。前端对接：QualityAPI.createRule，由 QualityRules 新建规则表单调用。"
    )
    @PostMapping("/rules")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<QualityRuleDTO> create(@RequestBody CreateQualityRuleVO rule) {
        return ApiResponse.ok(service.createRule(rule));
    }

    @Operation(
        summary = "获取质量规则详情",
        description = "用途：读取单条规则定义。前端对接：QualityAPI.getRule 已封装，当前页面未直接调用详情接口。"
    )
    @GetMapping("/rules/{id}")
    public ApiResponse<QualityRuleDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getRule(id));
    }

    @Operation(
        summary = "查询质量规则列表",
        description = "用途：返回质量规则清单。前端对接：QualityAPI.listRules，由 QualityRules 和 QualityResults 使用。"
    )
    @GetMapping("/rules")
    public ApiResponse<List<QualityRuleDTO>> list() {
        return ApiResponse.ok(service.listRules());
    }

    @Operation(
        summary = "按目标资产查询质量规则",
        description = "用途：返回指定资产 FQN 绑定的质量规则。前端对接：QualityAPI.rulesByTarget 已封装，当前页面未直接调用。"
    )
    @GetMapping("/rules/by-target")
    public ApiResponse<List<QualityRuleDTO>> byTarget(@RequestParam String fqn) {
        return ApiResponse.ok(service.rulesFor(fqn));
    }

    @Operation(
        summary = "运行质量规则",
        description = "用途：手动触发单条质量规则执行。前端对接：QualityAPI.runRule，由 QualityRules 运行按钮调用。"
    )
    @PostMapping("/rules/{id}/run")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<QualityRunResultDTO> run(@PathVariable UUID id) {
        return ApiResponse.ok(service.runRule(id));
    }

    @Operation(
        summary = "记录质量稽核结果",
        description = "用途：由调度、任务或运维流程写入规则执行结果。前端对接：当前 QualityAPI 未封装，供后台执行器或运维工具使用。"
    )
    @PostMapping("/results")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<QualityRunResultDTO> record(@RequestBody RunResult r) {
        return ApiResponse.ok(service.recordResult(r));
    }

    @Operation(
        summary = "查询规则近期结果",
        description = "用途：返回指定规则的近期稽核结果。前端对接：QualityAPI.recentResults，由 QualityResults 使用。"
    )
    @GetMapping("/results/{ruleId}")
    public ApiResponse<List<QualityRunResultDTO>> recent(@PathVariable UUID ruleId) {
        return ApiResponse.ok(service.recentResults(ruleId));
    }

    @Operation(
        summary = "查询未关闭质量告警",
        description = "用途：返回当前租户打开状态的质量告警。前端对接：QualityAPI.openAlerts，由 GateFailed 页面加载真实门禁告警。"
    )
    @GetMapping("/alerts")
    public ApiResponse<List<QualityAlertDTO>> alerts() {
        return ApiResponse.ok(service.openAlerts());
    }

    @Operation(
        summary = "关闭质量告警",
        description = "用途：将质量告警标记为已处理。前端对接：QualityAPI.closeAlert，由 GateFailed 页面处理告警时调用。"
    )
    @PostMapping("/alerts/{id}/close")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<Void> closeAlert(@PathVariable UUID id) {
        service.closeAlert(id);
        return ApiResponse.ok();
    }
}
