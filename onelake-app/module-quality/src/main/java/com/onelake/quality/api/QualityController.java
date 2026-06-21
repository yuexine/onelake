package com.onelake.quality.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.quality.api.vo.CreateQualityRuleVO;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.dto.QualityAlertDTO;
import com.onelake.quality.dto.QualityRuleDTO;
import com.onelake.quality.dto.QualityRunResultDTO;
import com.onelake.quality.service.QualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityController {

    private final QualityService service;

    @PostMapping("/rules")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<QualityRuleDTO> create(@RequestBody CreateQualityRuleVO rule) {
        return ApiResponse.ok(service.createRule(rule));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<QualityRuleDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getRule(id));
    }

    @GetMapping("/rules")
    public ApiResponse<List<QualityRuleDTO>> list() {
        return ApiResponse.ok(service.listRules());
    }

    @GetMapping("/rules/by-target")
    public ApiResponse<List<QualityRuleDTO>> byTarget(@RequestParam String fqn) {
        return ApiResponse.ok(service.rulesFor(fqn));
    }

    @PostMapping("/rules/{id}/run")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<QualityRunResultDTO> run(@PathVariable UUID id) {
        return ApiResponse.ok(service.runRule(id));
    }

    @PostMapping("/results")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<QualityRunResultDTO> record(@RequestBody RunResult r) {
        return ApiResponse.ok(service.recordResult(r));
    }

    @GetMapping("/results/{ruleId}")
    public ApiResponse<List<QualityRunResultDTO>> recent(@PathVariable UUID ruleId) {
        return ApiResponse.ok(service.recentResults(ruleId));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<QualityAlertDTO>> alerts() {
        return ApiResponse.ok(service.openAlerts());
    }

    @PostMapping("/alerts/{id}/close")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<Void> closeAlert(@PathVariable UUID id) {
        service.closeAlert(id);
        return ApiResponse.ok();
    }
}
