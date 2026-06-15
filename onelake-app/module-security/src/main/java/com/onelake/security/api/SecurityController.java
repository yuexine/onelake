package com.onelake.security.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.security.domain.entity.AccessGrant;
import com.onelake.security.domain.entity.ApprovalRequest;
import com.onelake.security.domain.entity.MaskingPolicy;
import com.onelake.security.service.SecurityService;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/approvals")
    @PreAuthorize("hasAnyRole('CONSUMER','DE')")
    public ApiResponse<ApprovalRequest> apply(@RequestParam String assetFqn,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(service.applyAccess(assetFqn, payload == null ? Map.of() : payload));
    }

    @GetMapping("/approvals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SEC')")
    public ApiResponse<List<ApprovalRequest>> pending() {
        return ApiResponse.ok(service.pendingApprovals());
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
}
