package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.dto.SourceSchemaSnapshotDTO;
import com.onelake.integration.service.SourceSchemaSnapshotService;
import com.onelake.integration.service.SourceSchemaSnapshotService.DriftResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 数据源 Schema 快照与漂移检测 API。
 *
 * <p>路径前缀：{@code /api/v1/integration/datasources/{sourceId}/schema-snapshots}
 * （挂在数据源下，符合 RESTful 资源嵌套）
 */
@RestController
@RequestMapping("/api/v1/integration/datasources/{sourceId}/schema-snapshots")
@RequiredArgsConstructor
@Tag(name = "源端 Schema 快照", description = "数据源表结构快照、历史查询和漂移检测接口。")
public class SourceSchemaSnapshotController {

    private final SourceSchemaSnapshotService service;

    /** 手动触发一次快照。 */
    @Operation(
        summary = "采集源端 Schema 快照",
        description = "用途：对指定数据源和对象名立即生成一次字段结构快照。前端对接：IntegrationAPI.captureSnapshot 已封装，当前页面未直接调用，供采集任务预检查或漂移页使用。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SourceSchemaSnapshotDTO> capture(
            @PathVariable UUID sourceId,
            @RequestParam String objectName) {
        return ApiResponse.ok(service.capture(sourceId, objectName));
    }

    /** 列出某数据源的全部快照（可选按 objectName 过滤）。 */
    @Operation(
        summary = "查询源端 Schema 快照列表",
        description = "用途：按数据源和可选对象名查看快照历史。前端对接：IntegrationAPI.listSnapshots 已封装，当前页面未直接调用，供 schema 漂移页面使用。"
    )
    @GetMapping
    public ApiResponse<List<SourceSchemaSnapshotDTO>> list(
            @PathVariable UUID sourceId,
            @RequestParam(required = false) String objectName) {
        return ApiResponse.ok(objectName == null || objectName.isBlank()
                ? service.listBySource(sourceId)
                : service.listByObject(sourceId, objectName));
    }

    /** 检测最近两次快照的漂移。 */
    @Operation(
        summary = "检测源端 Schema 漂移",
        description = "用途：比较最近两次快照并返回新增、删除、类型变化等漂移结果。前端对接：当前 API 聚合层未封装 drift 调用，供后续 SchemaChangeApproval 页面接入。"
    )
    @GetMapping("/drift")
    public ApiResponse<DriftResult> drift(
            @PathVariable UUID sourceId,
            @RequestParam String objectName) {
        return ApiResponse.ok(service.detectDrift(sourceId, objectName));
    }
}
