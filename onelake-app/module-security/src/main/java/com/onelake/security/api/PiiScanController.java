package com.onelake.security.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.PiiScanRecord;
import com.onelake.security.service.PiiScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security/pii-scan")
@RequiredArgsConstructor
@Tag(name = "PII 识别", description = "敏感字段扫描记录查询、确认和手动扫描接口。")
public class PiiScanController {

    private final PiiScanService service;

    @Operation(
        summary = "查询 PII 扫描记录",
        description = "用途：返回当前租户全部敏感字段识别记录。前端对接：SecurityAPI.listPiiScan，由 PiiScan 页面通过 fallback hook 加载。"
    )
    @GetMapping
    public ApiResponse<List<PiiScanRecord>> list() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.listAll(tid));
    }

    @Operation(
        summary = "查询待确认 PII 记录",
        description = "用途：返回尚未人工确认的敏感字段识别记录。前端对接：SecurityAPI.listPiiPending 已封装，当前页面未直接调用。"
    )
    @GetMapping("/pending")
    public ApiResponse<List<PiiScanRecord>> listPending() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.listPending(tid));
    }

    @Operation(
        summary = "确认 PII 识别结果",
        description = "用途：安全角色批量确认敏感字段识别记录。前端对接：SecurityAPI.confirmPii 已封装，当前页面未直接调用确认入口。"
    )
    @PostMapping("/confirm")
    @PreAuthorize("hasRole('SEC')")
    public ApiResponse<Void> confirm(@RequestBody List<UUID> recordIds) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        service.confirm(tid, recordIds);
        return ApiResponse.ok(null);
    }

    @Operation(
        summary = "触发 PII 扫描",
        description = "用途：对指定表 FQN 排队执行敏感字段扫描。前端对接：当前 SecurityAPI 未封装，供后续资产详情或安全页面触发。"
    )
    @PostMapping("/scan")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Integer> scan(@RequestParam String tableFqn) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(service.enqueueScan(tid, tableFqn));
    }
}
