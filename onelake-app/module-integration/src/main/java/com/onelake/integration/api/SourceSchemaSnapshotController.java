package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.dto.SourceSchemaSnapshotDTO;
import com.onelake.integration.service.SourceSchemaSnapshotService;
import com.onelake.integration.service.SourceSchemaSnapshotService.DriftResult;
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
public class SourceSchemaSnapshotController {

    private final SourceSchemaSnapshotService service;

    /** 手动触发一次快照。 */
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SourceSchemaSnapshotDTO> capture(
            @PathVariable UUID sourceId,
            @RequestParam String objectName) {
        return ApiResponse.ok(service.capture(sourceId, objectName));
    }

    /** 列出某数据源的全部快照（可选按 objectName 过滤）。 */
    @GetMapping
    public ApiResponse<List<SourceSchemaSnapshotDTO>> list(
            @PathVariable UUID sourceId,
            @RequestParam(required = false) String objectName) {
        return ApiResponse.ok(objectName == null || objectName.isBlank()
                ? service.listBySource(sourceId)
                : service.listByObject(sourceId, objectName));
    }

    /** 检测最近两次快照的漂移。 */
    @GetMapping("/drift")
    public ApiResponse<DriftResult> drift(
            @PathVariable UUID sourceId,
            @RequestParam String objectName) {
        return ApiResponse.ok(service.detectDrift(sourceId, objectName));
    }
}
