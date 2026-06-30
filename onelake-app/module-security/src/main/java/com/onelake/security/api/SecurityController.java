package com.onelake.security.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.security.domain.entity.AccessGrant;
import com.onelake.security.domain.entity.ApprovalRequest;
import com.onelake.security.domain.entity.MaskingPolicy;
import com.onelake.security.service.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
@Tag(name = "资产安全与审批", description = "脱敏策略、访问授权和审批流接口。")
public class SecurityController {

    private final SecurityService service;

    @Operation(
        summary = "创建脱敏策略",
        description = "用途：为字段或资产配置脱敏规则。前端对接：当前 SecurityAPI 未封装创建入口，Masking 页面仍以原型展示为主。"
    )
    @PostMapping("/masking-policies")
    @PreAuthorize("hasRole('SEC')")
    public ApiResponse<MaskingPolicy> createMasking(@RequestBody MaskingPolicy p) {
        return ApiResponse.ok(service.createMasking(p));
    }

    @Operation(
        summary = "解析资产脱敏策略",
        description = "用途：按资产 FQN 返回当前生效的脱敏策略。前端对接：当前 SecurityAPI 未封装，运行时由 SQL/API 服务侧也会使用安全策略。"
    )
    @GetMapping("/masking-policies/resolve")
    public ApiResponse<MaskingPolicy> resolve(@RequestParam String fqn) {
        return ApiResponse.ok(service.resolveMasking(fqn));
    }

    @Operation(
        summary = "查询我的授权",
        description = "用途：返回当前用户已获得的数据访问授权。前端对接：SecurityAPI.myGrants，由 SqlWorkbench 访问状态面板使用。"
    )
    @GetMapping("/grants/me")
    public ApiResponse<List<AccessGrant>> myGrants() {
        return ApiResponse.ok(service.myGrants());
    }

    @Operation(
        summary = "查询授权列表",
        description = "用途：管理员或安全角色查看授权台账。前端对接：SecurityAPI.listGrants，由 system/Approvals 授权管理页使用。"
    )
    @GetMapping("/grants")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<List<AccessGrant>> grants(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listGrants(status));
    }

    @Operation(
        summary = "创建授权",
        description = "用途：管理员或安全角色直接授予主体资产访问权限。前端对接：SecurityAPI.createGrant，由 system/Approvals 手动授权表单调用。"
    )
    @PostMapping("/grants")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> createGrant(@RequestParam UUID subjectId,
                                                @RequestParam String assetFqn,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.createGrant(subjectId, assetFqn, payload == null ? Map.of() : payload));
    }

    @Operation(
        summary = "撤销授权",
        description = "用途：将已有资产授权置为失效。前端对接：SecurityAPI.revokeGrant，由 system/Approvals 授权台账操作调用。"
    )
    @PostMapping("/grants/{id}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> revokeGrant(@PathVariable UUID id,
                                                @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.revokeGrant(id, comment));
    }

    @Operation(
        summary = "延长授权",
        description = "用途：延长已有资产授权有效期。前端对接：SecurityAPI.extendGrant，由 system/Approvals 授权台账操作调用。"
    )
    @PostMapping("/grants/{id}/extend")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> extendGrant(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "30") int durationDays) {
        return ApiResponse.ok(service.extendGrant(id, durationDays));
    }

    @Operation(
        summary = "提交访问申请",
        description = "用途：当前用户为某资产提交访问审批。前端对接：SecurityAPI.applyAccess，由 SqlWorkbench 的访问申请弹窗调用。"
    )
    @PostMapping("/approvals")
    @PreAuthorize("hasAnyRole('CONSUMER','DE')")
    public ApiResponse<ApprovalRequest> apply(@RequestParam String assetFqn,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.applyAccess(assetFqn, payload == null ? Map.of() : payload));
    }

    @Operation(
        summary = "提交 Schema 变更申请",
        description = "用途：为物理字段增删改提交审批单，只登记审批与影响信息，不直接执行 Iceberg DDL。前端对接：SecurityAPI.applySchemaChange，由 TableDetail 的 Schema 变更入口调用。"
    )
    @PostMapping("/schema-change-approvals")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<ApprovalRequest> applySchemaChange(@RequestParam String assetFqn,
                                                          @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.applySchemaChange(assetFqn, payload == null ? Map.of() : payload));
    }

    @Operation(
        summary = "查询我的审批申请",
        description = "用途：返回当前用户发起或可见的审批申请分页。前端对接：SecurityAPI.myApprovals，由 SqlWorkbench 访问申请状态面板使用。"
    )
    @GetMapping("/approvals/me")
    @PreAuthorize("hasAnyRole('CONSUMER','DE','ADMIN','SEC')")
    public ApiResponse<Page<ApprovalRequest>> myApprovals(@RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        return ApiResponse.ok(service.myApprovals(status, PageRequest.of(safePage, safeSize)));
    }

    @Operation(
        summary = "撤回我的审批申请",
        description = "用途：申请人撤回未完成审批。前端对接：SecurityAPI.cancelApproval，由 SqlWorkbench 申请状态列表调用。"
    )
    @PostMapping("/approvals/{id}/cancel")
    @PreAuthorize("hasAnyRole('CONSUMER','DE','ADMIN','SEC')")
    public ApiResponse<Void> cancel(@PathVariable UUID id,
                                    @RequestParam(required = false) String comment) {
        service.cancelMyApproval(id, comment);
        return ApiResponse.ok();
    }

    @Operation(
        summary = "查询待审批列表",
        description = "用途：管理员或安全角色查看待处理申请。前端对接：SecurityAPI.pendingApprovals，由 dashboard 和 system/Approvals 使用。"
    )
    @GetMapping("/approvals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<List<ApprovalRequest>> pending() {
        return ApiResponse.ok(service.pendingApprovals());
    }

    @Operation(
        summary = "查询已处理审批列表",
        description = "用途：管理员或安全角色查看已处理审批分页。前端对接：SecurityAPI.processedApprovals，由 system/Approvals 使用。"
    )
    @GetMapping("/approvals/processed")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<Page<ApprovalRequest>> processed(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        return ApiResponse.ok(service.processedApprovals(status, PageRequest.of(safePage, safeSize)));
    }

    @Operation(
        summary = "通过审批",
        description = "用途：审批通过并按流程创建访问授权。前端对接：SecurityAPI.approveApproval，由 system/Approvals 单条和批量通过操作调用。"
    )
    @PostMapping("/approvals/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> approve(@PathVariable UUID id,
                                            @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.approve(id, TenantContext.getUserId(), comment));
    }

    @Operation(
        summary = "拒绝审批",
        description = "用途：拒绝访问申请并记录处理意见。前端对接：SecurityAPI.rejectApproval，由 system/Approvals 单条和批量拒绝操作调用。"
    )
    @PostMapping("/approvals/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<Void> reject(@PathVariable UUID id,
                                    @RequestParam(required = false) String comment) {
        service.reject(id, TenantContext.getUserId(), comment);
        return ApiResponse.ok();
    }

    @Operation(
        summary = "转交审批",
        description = "用途：把审批转交给指定下一处理人。前端对接：SecurityAPI.transferApproval，由 system/Approvals 转交操作调用。"
    )
    @PostMapping("/approvals/{id}/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<ApprovalRequest> transfer(@PathVariable UUID id,
                                                 @RequestParam(required = false) UUID nextApproverId,
                                                 @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.transferApproval(id, nextApproverId, comment));
    }

    @Operation(
        summary = "审批加签",
        description = "用途：为当前审批追加安全复核等加签步骤。前端对接：SecurityAPI.addSignApproval，由 system/Approvals 加签操作调用。"
    )
    @PostMapping("/approvals/{id}/add-sign")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<ApprovalRequest> addSign(@PathVariable UUID id,
                                                @RequestParam(required = false) String role,
                                                @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.addSign(id, role, comment));
    }
}
