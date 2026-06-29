package com.onelake.analytics.api;

import com.onelake.analytics.api.vo.DatasetRequest;
import com.onelake.analytics.dto.DataBinding;
import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.dto.QueryResult;
import com.onelake.analytics.service.DatasetQueryService;
import com.onelake.analytics.service.DatasetService;
import com.onelake.common.api.ApiResponse;
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

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * 数据集 Controller。
 * 路径前缀：/api/v1/analytics/datasets
 */
@RestController
@RequestMapping("/api/v1/analytics/datasets")
@RequiredArgsConstructor
@Tag(name = "数据分析-数据集", description = "数据集 CRUD + 查询接口")
public class DatasetController {

    private final DatasetService service;
    private final DatasetQueryService queryService;

    @Operation(summary = "创建数据集")
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DatasetDTO> create(@Valid @RequestBody DatasetRequest vo) {
        return ApiResponse.ok(service.create(vo));
    }

    @Operation(summary = "更新数据集")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DatasetDTO> update(@PathVariable UUID id, @RequestBody DatasetRequest vo) {
        return ApiResponse.ok(service.update(id, vo));
    }

    @Operation(summary = "获取数据集详情")
    @GetMapping("/{id}")
    public ApiResponse<DatasetDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(summary = "列出当前租户的数据集")
    @GetMapping
    public ApiResponse<List<DatasetDTO>> list(@RequestParam(required = false) String keyword) {
        List<DatasetDTO> all = service.list();
        if (keyword == null || keyword.isBlank()) return ApiResponse.ok(all);
        String kw = keyword.toLowerCase();
        return ApiResponse.ok(all.stream().filter(d -> d.getName().toLowerCase().contains(kw)).toList());
    }

    @Operation(summary = "删除数据集")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "执行数据集查询（推送到 Trino 或 PostgREST）")
    @PostMapping("/{id}/query")
    public ApiResponse<QueryResult> query(@PathVariable UUID id,
                                          @RequestBody(required = false) DataBinding binding,
                                          @RequestParam(required = false) UUID dashboardId) {
        return ApiResponse.ok(queryService.query(id, binding, dashboardId));
    }
}
