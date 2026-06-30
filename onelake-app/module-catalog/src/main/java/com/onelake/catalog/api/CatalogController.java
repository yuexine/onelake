package com.onelake.catalog.api;

import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.AssetDetailDTO;
import com.onelake.catalog.dto.AssetMetadataUpdateRequest;
import com.onelake.catalog.dto.AssetMaintenanceAssessmentDTO;
import com.onelake.catalog.dto.AssetMaintenanceRequest;
import com.onelake.catalog.dto.AssetMaintenanceResultDTO;
import com.onelake.catalog.dto.ImpactReportDTO;
import com.onelake.catalog.dto.LineageGraphDTO;
import com.onelake.catalog.dto.SchemaChangeExecutionResultDTO;
import com.onelake.catalog.dto.TableCreateRequest;
import com.onelake.catalog.service.CatalogAssetDetailService;
import com.onelake.catalog.service.CatalogColumnRefreshService;
import com.onelake.catalog.service.CatalogLineageService;
import com.onelake.catalog.service.CatalogMaintenanceService;
import com.onelake.catalog.service.CatalogSchemaChangeService;
import com.onelake.catalog.service.CatalogService;
import com.onelake.catalog.service.CatalogSyncService;
import com.onelake.catalog.service.CatalogTableService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
@Tag(name = "数据目录", description = "资产检索、资产详情、血缘、表创建和湖仓维护接口。")
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogAssetDetailService detailService;
    private final CatalogLineageService lineageService;
    private final CatalogTableService tableService;
    private final CatalogSyncService syncService;
    private final CatalogColumnRefreshService columnRefreshService;
    private final CatalogMaintenanceService maintenanceService;
    private final CatalogSchemaChangeService schemaChangeService;
    private final NotificationService notificationService;

    @Operation(
        summary = "获取资产摘要",
        description = "用途：返回目录资产的基础字段和展示摘要。前端对接：CatalogAPI.getAsset，由 Catalog AssetDetail 页面使用；湖仓详情优先使用 detail 接口。"
    )
    @GetMapping("/assets/{id}")
    public ApiResponse<AssetDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(catalogService.getAsset(id));
    }

    @Operation(
        summary = "获取资产详情",
        description = "用途：返回资产字段、质量、安全、血缘和画像等详情。前端对接：CatalogAPI.getAssetDetail，由 TableDetail 和 TableWizard 使用。"
    )
    @GetMapping("/assets/{id}/detail")
    public ApiResponse<AssetDetailDTO> detail(@PathVariable UUID id) {
        return ApiResponse.ok(detailService.getDetail(id));
    }

    @Operation(
        summary = "更新资产元数据",
        description = "用途：更新表描述、业务域、负责人展示名和字段描述/密级/PII 等治理元数据，不执行物理 DDL。前端对接：CatalogAPI.updateAssetMetadata，由 TableDetail Schema 页调用。"
    )
    @PatchMapping("/assets/{id}/metadata")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<AssetDTO> updateMetadata(@PathVariable UUID id,
                                                @RequestBody AssetMetadataUpdateRequest request) {
        return ApiResponse.ok(catalogService.updateMetadata(id, request));
    }

    @Operation(
        summary = "查询资产列表",
        description = "用途：按可选湖仓层级、关键字或业务术语查询目录资产。前端对接：CatalogAPI.listAssets，由 CatalogSearch、Tables、SqlWorkbench、QualityRules 等页面使用。"
    )
    @GetMapping("/assets")
    public ApiResponse<List<AssetDTO>> list(@RequestParam(required = false) String layer,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String term) {
        return ApiResponse.ok(catalogService.list(layer, keyword, term));
    }

    @Operation(
        summary = "创建湖仓表",
        description = "用途：在湖仓中创建 ODS/DWD/ADS 表并登记目录资产。前端对接：CatalogAPI.createTable，由 TableWizard 建表流程调用。"
    )
    @PostMapping("/tables")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<AssetDTO> createTable(@RequestBody TableCreateRequest request) {
        return ApiResponse.ok(tableService.createTable(request));
    }

    @Operation(
        summary = "执行已审批 Schema 变更",
        description = "用途：根据已通过的 SCHEMA_CHANGE 审批单执行 Iceberg ALTER TABLE，并回写 Catalog 字段快照。前端对接：CatalogAPI.executeSchemaChange，由审批中心通过后调用。"
    )
    @PostMapping("/schema-changes/{approvalId}/execute")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<SchemaChangeExecutionResultDTO> executeSchemaChange(@PathVariable UUID approvalId) {
        return ApiResponse.ok(schemaChangeService.executeApproved(approvalId));
    }

    @Operation(
        summary = "查询下游血缘",
        description = "用途：按资产 FQN 返回下游依赖节点。前端对接：CatalogAPI.downstream 已封装，当前页面血缘仍以页面状态/原型数据为主，供后续 LineageGraph 接入。"
    )
    @GetMapping("/lineage/downstream")
    public ApiResponse<List<String>> downstream(@RequestParam String fqn) {
        return ApiResponse.ok(catalogService.downstream(TenantContext.getTenantId(), fqn));
    }

    @Operation(
        summary = "查询血缘图",
        description = "用途：按根资产 FQN + 方向 + 深度返回 X6 节点 / 边结构。前端对接：CatalogAPI.lineageGraph，LineageGraph 页面使用 X6 渲染。"
    )
    @GetMapping("/lineage/graph")
    public ApiResponse<LineageGraphDTO> lineageGraph(
        @RequestParam String fqn,
        @RequestParam(defaultValue = "BOTH") String direction,
        @RequestParam(defaultValue = "3") Integer depth
    ) {
        return ApiResponse.ok(lineageService.graph(TenantContext.getTenantId(), fqn, direction, depth));
    }

    @Operation(
        summary = "影响分析",
        description = "用途：按根资产 FQN 统计下游影响（直接/间接 + 受影响任务/API/订阅方 + severity）。前端对接：CatalogAPI.lineageImpact，LineageGraph 影响分析 Drawer 使用。"
    )
    @GetMapping("/lineage/impact")
    public ApiResponse<ImpactReportDTO> lineageImpact(@RequestParam String fqn) {
        return ApiResponse.ok(lineageService.impact(TenantContext.getTenantId(), fqn));
    }

    @Operation(
        summary = "导出影响报告 CSV",
        description = "用途：按根资产 FQN 导出影响清单 CSV。前端对接：CatalogAPI.exportImpact，LineageGraph 「导出影响报告」按钮调用。"
    )
    @GetMapping(value = "/lineage/impact/export", produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public void exportImpact(@RequestParam String fqn, HttpServletResponse response) throws java.io.IOException {
        String csv = lineageService.exportImpactCsv(TenantContext.getTenantId(), fqn);
        String filename = "impact-" + (fqn == null ? "unknown" : fqn.replaceAll("[^A-Za-z0-9._-]", "_")) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        // 加 UTF-8 BOM 让 Excel 正确识别中文
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    @Operation(
        summary = "通知影响分析结果",
        description = "用途：把当前影响分析结果作为站内消息通知给发起者（去重：同 fqn 只通知一次）。前端对接：CatalogAPI.notifyImpact，LineageGraph Drawer 内按钮调用。"
    )
    @PostMapping("/lineage/impact/notify")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<Map<String, Object>> notifyImpact(@RequestParam String fqn) {
        ImpactReportDTO report = lineageService.impact(TenantContext.getTenantId(), fqn);
        String summary = lineageService.buildImpactSummary(report);
        boolean created = notificationService.notifyImpactAnalysis(
            TenantContext.getUserId(),
            fqn,
            report.severity(),
            summary
        );
        return ApiResponse.ok(Map.of(
            "notified", created,
            "severity", report.severity(),
            "rootFqn", fqn,
            "message", created ? "已发送站内通知" : "同资产的影响通知已存在，未重复发送"
        ));
    }

    @Operation(
        summary = "同步外部元数据到目录",
        description = "用途：从元数据源同步表资产到目录。前端对接：CatalogAPI.sync 已封装，当前页面未直接调用，供管理入口或运维操作使用。"
    )
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> sync() {
        int n = syncService.syncTables();
        return ApiResponse.ok(Map.of("synced", n));
    }

    @Operation(
        summary = "刷新缺失字段画像",
        description = "用途：补齐目录资产缺失的字段信息。前端对接：CatalogAPI.refreshColumns，由 SqlWorkbench 在资产字段为空时触发刷新。"
    )
    @PostMapping("/assets/refresh-columns")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<Map<String, Object>> refreshColumns() {
        int n = columnRefreshService.refreshMissingColumns();
        return ApiResponse.ok(Map.of("refreshed", n));
    }

    @Operation(
        summary = "查询 DWD 维护评估列表",
        description = "用途：返回可维护资产的压缩、小文件、统计信息等评估结果。前端对接：CatalogAPI.listMaintenance，由 OptimizeCenter 使用。"
    )
    @GetMapping("/assets/maintenance")
    public ApiResponse<List<AssetMaintenanceAssessmentDTO>> maintenanceAssessments() {
        return ApiResponse.ok(maintenanceService.listDwdAssessments());
    }

    @Operation(
        summary = "查询单资产维护评估",
        description = "用途：返回单个资产的维护建议和可执行操作。前端对接：CatalogAPI.getMaintenance，由 TableDetail 加载维护卡片使用。"
    )
    @GetMapping("/assets/{id}/maintenance")
    public ApiResponse<AssetMaintenanceAssessmentDTO> maintenanceAssessment(@PathVariable UUID id) {
        return ApiResponse.ok(maintenanceService.assess(id));
    }

    @Operation(
        summary = "执行资产维护操作",
        description = "用途：对湖仓表执行 OPTIMIZE、EXPIRE_SNAPSHOTS、REMOVE_ORPHAN_FILES 维护动作。前端对接：CatalogAPI.runMaintenance，由 OptimizeCenter 和 TableDetail 调用。"
    )
    @PostMapping("/assets/{id}/maintenance")
    @PreAuthorize("hasAnyRole('ADMIN','DE')")
    public ApiResponse<AssetMaintenanceResultDTO> runMaintenance(
        @PathVariable UUID id,
        @RequestBody(required = false) AssetMaintenanceRequest request
    ) {
        return ApiResponse.ok(maintenanceService.runMaintenance(id, request));
    }
}
