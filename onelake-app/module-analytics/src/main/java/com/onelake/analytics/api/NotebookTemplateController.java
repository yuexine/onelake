package com.onelake.analytics.api;

import com.onelake.analytics.domain.entity.NotebookTemplate;
import com.onelake.analytics.domain.enums.TemplateCategory;
import com.onelake.analytics.service.NotebookTemplateService;
import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 算法模板 Controller。
 * 路径：/api/v1/analytics/notebook-templates
 */
@RestController
@RequestMapping("/api/v1/analytics/notebook-templates")
@RequiredArgsConstructor
@Tag(name = "数据分析-算法模板", description = "Notebook 算法模板（KMeans / Prophet / RFM 等）")
public class NotebookTemplateController {

    private final NotebookTemplateService service;

    @Operation(summary = "列出可见模板（平台预置 + 当前租户自定义）")
    @GetMapping
    public ApiResponse<List<NotebookTemplate>> list(@RequestParam(required = false) TemplateCategory category) {
        return ApiResponse.ok(service.list(category));
    }

    @Operation(summary = "获取模板详情")
    @GetMapping("/{id}")
    public ApiResponse<NotebookTemplate> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(summary = "创建租户自定义模板")
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<NotebookTemplate> create(
            @RequestParam String name,
            @RequestParam TemplateCategory category,
            @RequestParam(required = false) String description,
            @RequestParam String storagePath,
            @RequestParam(required = false) String paramsSchemaJson,
            @RequestParam(required = false) String kernel) {
        return ApiResponse.ok(service.create(name, category, description, storagePath, paramsSchemaJson, kernel));
    }

    @Operation(summary = "删除模板（仅租户自定义可删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
