package com.onelake.security.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.PiiScanRecord;
import com.onelake.security.service.PiiScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security/pii-scan")
@RequiredArgsConstructor
public class PiiScanController {

    private final PiiScanService service;

    @GetMapping
    public ApiResponse<List<PiiScanRecord>> list() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.listAll(tid));
    }

    @GetMapping("/pending")
    public ApiResponse<List<PiiScanRecord>> listPending() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.listPending(tid));
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('SEC')")
    public ApiResponse<Void> confirm(@RequestBody List<UUID> recordIds) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        service.confirm(tid, recordIds);
        return ApiResponse.ok(null);
    }

    @PostMapping("/scan")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Integer> scan(@RequestParam String tableFqn) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.enqueueScan(tid, tableFqn));
    }
}
