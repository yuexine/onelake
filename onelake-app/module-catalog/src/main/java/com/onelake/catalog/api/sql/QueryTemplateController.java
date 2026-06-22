package com.onelake.catalog.api.sql;

import com.onelake.catalog.dto.sql.QueryTemplateDTO;
import com.onelake.catalog.dto.sql.QueryTemplateRenderRequest;
import com.onelake.catalog.dto.sql.QueryTemplateRenderResultDTO;
import com.onelake.catalog.dto.sql.QueryTemplateSaveRequest;
import com.onelake.catalog.service.sql.QueryTemplateService;
import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lakehouse/sql/templates")
@RequiredArgsConstructor
@Tag(name = "SQL 模板", description = "SQL 工作台模板的列表、保存、更新、删除和占位符渲染接口。")
public class QueryTemplateController {

    private final QueryTemplateService service;

    @Operation(
        summary = "查询 SQL 模板",
        description = "用途：返回可复用 SQL 模板列表。前端对接：SqlWorkbenchAPI.templates，由 SqlWorkbench 模板面板加载。"
    )
    @GetMapping
    public ApiResponse<List<QueryTemplateDTO>> list() {
        return ApiResponse.ok(service.listTemplates());
    }

    @Operation(
        summary = "创建 SQL 模板",
        description = "用途：将当前 SQL 保存为带占位符的模板。前端对接：SqlWorkbenchAPI.createTemplate，由 SqlWorkbench 保存为模板操作调用。"
    )
    @PostMapping
    public ApiResponse<QueryTemplateDTO> create(@Valid @RequestBody QueryTemplateSaveRequest request) {
        return ApiResponse.ok(service.saveTemplate(request));
    }

    @Operation(
        summary = "更新 SQL 模板",
        description = "用途：修改模板名称、分类、SQL 和占位符定义。前端对接：SqlWorkbenchAPI.updateTemplate 已封装，当前页面暂未直接调用编辑入口。"
    )
    @PutMapping("/{id}")
    public ApiResponse<QueryTemplateDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody QueryTemplateSaveRequest request
    ) {
        return ApiResponse.ok(service.updateTemplate(id, request));
    }

    @Operation(
        summary = "删除 SQL 模板",
        description = "用途：移除不再需要的模板。前端对接：SqlWorkbenchAPI.deleteTemplate，由 SqlWorkbench 模板列表调用。"
    )
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deleteTemplate(id);
        return ApiResponse.ok(null);
    }

    @Operation(
        summary = "渲染 SQL 模板",
        description = "用途：用参数替换模板占位符并返回可执行 SQL。前端对接：SqlWorkbenchAPI.renderTemplate，由 SqlWorkbench 模板参数弹窗调用。"
    )
    @PostMapping("/{id}/render")
    public ApiResponse<QueryTemplateRenderResultDTO> render(
        @PathVariable UUID id,
        @RequestBody(required = false) QueryTemplateRenderRequest request
    ) {
        QueryTemplateRenderRequest req = request == null
            ? new QueryTemplateRenderRequest(java.util.Map.of())
            : request;
        return ApiResponse.ok(service.renderTemplate(id, req));
    }
}
