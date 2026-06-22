package com.onelake.security.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.security.domain.entity.AccessGrant;
import com.onelake.security.domain.entity.ApprovalRequest;
import com.onelake.security.domain.entity.MaskingPolicy;
import com.onelake.security.service.SecurityService;
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
public class SecurityController {

    private final SecurityService service;

    @PostMapping("/masking-policies")
    @PreAuthorize("hasRole('SEC')")
    public ApiResponse<MaskingPolicy> createMasking(@RequestBody MaskingPolicy p) {
        return ApiResponse.ok(service.createMasking(p));
    }

    @GetMapping("/masking-policies/resolve")
    public ApiResponse<MaskingPolicy> resolve(@RequestParam String fqn) {
        return ApiResponse.ok(service.resolveMasking(fqn));
    }

    @GetMapping("/grants/me")
    public ApiResponse<List<AccessGrant>> myGrants() {
        return ApiResponse.ok(service.myGrants());
    }

    @GetMapping("/grants")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<List<AccessGrant>> grants(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listGrants(status));
    }

    @PostMapping("/grants")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> createGrant(@RequestParam UUID subjectId,
                                                @RequestParam String assetFqn,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.createGrant(subjectId, assetFqn, payload == null ? Map.of() : payload));
    }

    @PostMapping("/grants/{id}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> revokeGrant(@PathVariable UUID id,
                                                @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.revokeGrant(id, comment));
    }

    @PostMapping("/grants/{id}/extend")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> extendGrant(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "30") int durationDays) {
        return ApiResponse.ok(service.extendGrant(id, durationDays));
    }

    @PostMapping("/approvals")
    @PreAuthorize("hasAnyRole('CONSUMER','DE')")
    public ApiResponse<ApprovalRequest> apply(@RequestParam String assetFqn,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.applyAccess(assetFqn, payload == null ? Map.of() : payload));
    }

    @GetMapping("/approvals/me")
    @PreAuthorize("hasAnyRole('CONSUMER','DE','ADMIN','SEC')")
    public ApiResponse<Page<ApprovalRequest>> myApprovals(@RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        return ApiResponse.ok(service.myApprovals(status, PageRequest.of(safePage, safeSize)));
    }

    @PostMapping("/approvals/{id}/cancel")
    @PreAuthorize("hasAnyRole('CONSUMER','DE','ADMIN','SEC')")
    public ApiResponse<Void> cancel(@PathVariable UUID id,
                                    @RequestParam(required = false) String comment) {
        service.cancelMyApproval(id, comment);
        return ApiResponse.ok();
    }

    @GetMapping("/approvals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<List<ApprovalRequest>> pending() {
        return ApiResponse.ok(service.pendingApprovals());
    }

    @GetMapping("/approvals/processed")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<Page<ApprovalRequest>> processed(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        return ApiResponse.ok(service.processedApprovals(status, PageRequest.of(safePage, safeSize)));
    }

    @PostMapping("/approvals/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<AccessGrant> approve(@PathVariable UUID id,
                                            @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.approve(id, TenantContext.getUserId(), comment));
    }

    @PostMapping("/approvals/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<Void> reject(@PathVariable UUID id,
                                    @RequestParam(required = false) String comment) {
        service.reject(id, TenantContext.getUserId(), comment);
        return ApiResponse.ok();
    }

    @PostMapping("/approvals/{id}/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<ApprovalRequest> transfer(@PathVariable UUID id,
                                                 @RequestParam(required = false) UUID nextApproverId,
                                                 @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.transferApproval(id, nextApproverId, comment));
    }

    @PostMapping("/approvals/{id}/add-sign")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<ApprovalRequest> addSign(@PathVariable UUID id,
                                                @RequestParam(required = false) String role,
                                                @RequestParam(required = false) String comment) {
        return ApiResponse.ok(service.addSign(id, role, comment));
    }
}
