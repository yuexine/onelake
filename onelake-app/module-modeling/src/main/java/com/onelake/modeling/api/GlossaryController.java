package com.onelake.modeling.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.modeling.dto.BusinessTermBindingDTO;
import com.onelake.modeling.dto.BusinessTermBindingRequest;
import com.onelake.modeling.dto.BusinessTermDTO;
import com.onelake.modeling.dto.BusinessTermImpactDTO;
import com.onelake.modeling.dto.BusinessTermDecisionRequest;
import com.onelake.modeling.dto.BusinessTermRequest;
import com.onelake.modeling.dto.BusinessTermVersionDTO;
import com.onelake.modeling.dto.BusinessTermVersionDiffDTO;
import com.onelake.modeling.service.GlossaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/modeling/glossary")
@RequiredArgsConstructor
@Tag(name = "业务术语表", description = "业务术语、指标口径和术语到资产字段映射的生产级管理接口。")
public class GlossaryController {

    private final GlossaryService service;

    @Operation(
        summary = "查询业务术语",
        description = "用途：按关键字、业务域和状态查询业务术语。前端对接：GlossaryAPI.listTerms，由 Glossary 页面分类树和列表使用。"
    )
    @GetMapping("/terms")
    public ApiResponse<List<BusinessTermDTO>> listTerms(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) UUID domainId,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.list(keyword, domainId, status));
    }

    @Operation(
        summary = "创建业务术语",
        description = "用途：创建业务术语草稿。前端对接：GlossaryAPI.createTerm，由 Glossary 新建术语弹窗调用。"
    )
    @PostMapping("/terms")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermDTO> createTerm(@RequestBody BusinessTermRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @Operation(
        summary = "获取业务术语详情",
        description = "用途：读取术语定义、口径、同义词、审定状态和关联字段。前端对接：GlossaryAPI.getTerm，由 Glossary 详情面板使用。"
    )
    @GetMapping("/terms/{id}")
    public ApiResponse<BusinessTermDTO> getTerm(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(
        summary = "更新业务术语",
        description = "用途：编辑术语定义、口径、负责人和标签。前端对接：GlossaryAPI.updateTerm，由 Glossary 编辑弹窗使用。"
    )
    @PutMapping("/terms/{id}")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermDTO> updateTerm(@PathVariable UUID id, @RequestBody BusinessTermRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @Operation(
        summary = "提交术语审定",
        description = "用途：将草稿术语提交到审定状态。前端对接：GlossaryAPI.submitTerm，由 Glossary 状态动作调用。"
    )
    @PostMapping("/terms/{id}/submit")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermDTO> submitTerm(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @Operation(
        summary = "审定通过术语",
        description = "用途：将术语标记为已审定并生成版本快照。前端对接：GlossaryAPI.approveTerm，由 Glossary 审定动作调用。"
    )
    @PostMapping("/terms/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BusinessTermDTO> approveTerm(
        @PathVariable UUID id,
        @RequestBody(required = false) BusinessTermDecisionRequest request
    ) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.comment()));
    }

    @Operation(
        summary = "审定退回术语",
        description = "用途：退回待审定术语。前端对接：GlossaryAPI.rejectTerm，由 Glossary 审定动作调用。"
    )
    @PostMapping("/terms/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BusinessTermDTO> rejectTerm(
        @PathVariable UUID id,
        @RequestBody(required = false) BusinessTermDecisionRequest request
    ) {
        return ApiResponse.ok(service.reject(id, request == null ? null : request.comment()));
    }

    @Operation(
        summary = "废弃术语",
        description = "用途：将不再推荐使用的术语标记为废弃。前端对接：GlossaryAPI.deprecateTerm，由 Glossary 治理动作调用。"
    )
    @PostMapping("/terms/{id}/deprecate")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermDTO> deprecateTerm(
        @PathVariable UUID id,
        @RequestBody(required = false) BusinessTermDecisionRequest request
    ) {
        return ApiResponse.ok(service.deprecate(id, request == null ? null : request.comment()));
    }

    @Operation(
        summary = "查询术语绑定字段",
        description = "用途：返回术语关联的资产字段。前端对接：GlossaryAPI.listBindings，由 Glossary 关联字段区使用。"
    )
    @GetMapping("/terms/{id}/bindings")
    public ApiResponse<List<BusinessTermBindingDTO>> listBindings(@PathVariable UUID id) {
        return ApiResponse.ok(service.bindings(id));
    }

    @Operation(
        summary = "新增术语字段绑定",
        description = "用途：将术语绑定到 Catalog 资产或字段。前端对接：GlossaryAPI.bindTerm，由 Glossary 新增关联字段动作调用。"
    )
    @PostMapping("/terms/{id}/bindings")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermBindingDTO> bindTerm(
        @PathVariable UUID id,
        @RequestBody BusinessTermBindingRequest request
    ) {
        return ApiResponse.ok(service.bind(id, request));
    }

    @Operation(
        summary = "移除术语字段绑定",
        description = "用途：将术语字段绑定标记为失效。前端对接：GlossaryAPI.removeBinding，由 Glossary 关联字段区使用。"
    )
    @DeleteMapping("/bindings/{bindingId}")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<BusinessTermBindingDTO> removeBinding(@PathVariable UUID bindingId) {
        return ApiResponse.ok(service.markBindingStale(bindingId));
    }

    @Operation(
        summary = "按资产查询术语绑定",
        description = "用途：返回某资产字段绑定的业务术语。前端对接：GlossaryAPI.bindingsByAsset，由 AssetDetail Schema 术语列使用。"
    )
    @GetMapping("/bindings/by-asset")
    public ApiResponse<List<BusinessTermBindingDTO>> bindingsByAsset(@RequestParam String assetFqn) {
        return ApiResponse.ok(service.bindingsByAsset(assetFqn));
    }

    @Operation(
        summary = "查询术语版本",
        description = "用途：查看术语历次审定版本快照。前端对接：GlossaryAPI.termVersions，由 Glossary 版本历史区使用。"
    )
    @GetMapping("/terms/{id}/versions")
    public ApiResponse<List<BusinessTermVersionDTO>> versions(@PathVariable UUID id) {
        return ApiResponse.ok(service.versions(id));
    }

    @Operation(
        summary = "查询术语影响分析",
        description = "用途：聚合术语绑定字段、下游资产、质量规则、DaaS API、DAG、安全扫描和治理审批影响面。前端对接：GlossaryAPI.termImpact，由 Glossary 影响分析区使用。"
    )
    @GetMapping("/terms/{id}/impact")
    public ApiResponse<BusinessTermImpactDTO> impact(@PathVariable UUID id) {
        return ApiResponse.ok(service.impact(id));
    }

    @Operation(
        summary = "查询术语版本差异",
        description = "用途：返回最近审定版本之间或已审定快照与当前编辑态之间的字段差异。前端对接：GlossaryAPI.termVersionDiff，由 Glossary 版本差异区使用。"
    )
    @GetMapping("/terms/{id}/version-diff")
    public ApiResponse<BusinessTermVersionDiffDTO> versionDiff(@PathVariable UUID id) {
        return ApiResponse.ok(service.latestVersionDiff(id));
    }
}
