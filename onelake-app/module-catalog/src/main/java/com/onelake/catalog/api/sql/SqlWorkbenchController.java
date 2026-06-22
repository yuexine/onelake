package com.onelake.catalog.api.sql;

import com.onelake.catalog.dto.sql.SavedQueryDTO;
import com.onelake.catalog.dto.sql.SqlEstimateDTO;
import com.onelake.catalog.dto.sql.SqlExecuteRequest;
import com.onelake.catalog.dto.sql.SqlExecuteResultDTO;
import com.onelake.catalog.dto.sql.SqlQueryHistoryDTO;
import com.onelake.catalog.dto.sql.SqlSaveQueryRequest;
import com.onelake.catalog.service.sql.SqlWorkbenchService;
import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lakehouse/sql")
@RequiredArgsConstructor
@Tag(name = "SQL 工作台", description = "SQL 预估、提交、轮询、取消、导出和收藏查询接口。")
public class SqlWorkbenchController {

    private final SqlWorkbenchService service;

    @Operation(
        summary = "预估 SQL 扫描量",
        description = "用途：在执行前预估扫描量和资源组路由，提示大查询风险。前端对接：SqlWorkbenchAPI.estimate，由 SqlWorkbench 执行前调用。"
    )
    @PostMapping("/estimate")
    public ApiResponse<SqlEstimateDTO> estimate(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.estimate(request));
    }

    @Operation(
        summary = "同步执行 SQL",
        description = "用途：直接执行 SQL 并返回结果。前端对接：SqlWorkbenchAPI.execute 已封装，当前页面主流程使用 submit 轮询模式。"
    )
    @PostMapping("/execute")
    public ApiResponse<SqlExecuteResultDTO> execute(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.execute(request));
    }

    @Operation(
        summary = "提交 SQL 查询",
        description = "用途：创建查询历史并执行 SQL，返回查询 id 和结果状态。前端对接：SqlWorkbenchAPI.submit，由 SqlWorkbench 主执行按钮调用。"
    )
    @PostMapping("/queries")
    public ApiResponse<SqlExecuteResultDTO> submit(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.submit(request));
    }

    @Operation(
        summary = "查询 SQL 执行结果",
        description = "用途：按查询 id 轮询状态、结果行和错误信息。前端对接：SqlWorkbenchAPI.query，由 SqlWorkbench 对运行中查询轮询。"
    )
    @GetMapping("/queries/{id}")
    public ApiResponse<SqlExecuteResultDTO> query(@PathVariable UUID id) {
        return ApiResponse.ok(service.query(id));
    }

    @Operation(
        summary = "取消 SQL 查询",
        description = "用途：取消运行中查询或导出任务。前端对接：SqlWorkbenchAPI.cancel，由 SqlWorkbench 查询和导出取消按钮调用。"
    )
    @PostMapping("/queries/{id}/cancel")
    public ApiResponse<SqlExecuteResultDTO> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @Operation(
        summary = "导出 SQL 查询结果",
        description = "用途：执行 SQL 并以 CSV/TSV 流返回导出文件。前端对接：SqlWorkbenchAPI.export 已封装，同时 SqlWorkbench 中存在直接 fetch 导出调用。"
    )
    @PostMapping("/export")
    public void export(
        @Valid @RequestBody SqlExecuteRequest request,
        @RequestParam(defaultValue = "csv") String format,
        @RequestParam(required = false) Integer maxRows,
        HttpServletResponse response
    ) throws IOException {
        service.export(request, format, maxRows, response);
    }

    @Operation(
        summary = "查询 SQL 历史",
        description = "用途：返回当前用户近期 SQL 执行历史。前端对接：SqlWorkbenchAPI.history，由 SqlWorkbench 历史面板使用。"
    )
    @GetMapping("/history")
    public ApiResponse<List<SqlQueryHistoryDTO>> history() {
        return ApiResponse.ok(service.history());
    }

    @Operation(
        summary = "查询已保存 SQL",
        description = "用途：返回当前用户保存或共享的 SQL。前端对接：SqlWorkbenchAPI.savedQueries，由 SqlWorkbench 收藏查询面板使用。"
    )
    @GetMapping("/saved-queries")
    public ApiResponse<List<SavedQueryDTO>> savedQueries() {
        return ApiResponse.ok(service.savedQueries());
    }

    @Operation(
        summary = "保存 SQL",
        description = "用途：将当前 SQL 保存为个人或共享查询。前端对接：SqlWorkbenchAPI.saveQuery，由 SqlWorkbench 保存操作调用。"
    )
    @PostMapping("/saved-queries")
    public ApiResponse<SavedQueryDTO> saveQuery(@Valid @RequestBody SqlSaveQueryRequest request) {
        return ApiResponse.ok(service.saveQuery(request));
    }

    @Operation(
        summary = "更新已保存 SQL",
        description = "用途：修改收藏 SQL 的名称、内容或共享状态。前端对接：SqlWorkbenchAPI.updateSavedQuery，由 SqlWorkbench 更新和共享切换操作调用。"
    )
    @PutMapping("/saved-queries/{id}")
    public ApiResponse<SavedQueryDTO> updateSavedQuery(
        @PathVariable UUID id,
        @Valid @RequestBody SqlSaveQueryRequest request
    ) {
        return ApiResponse.ok(service.updateSavedQuery(id, request));
    }

    @Operation(
        summary = "删除已保存 SQL",
        description = "用途：删除不再需要的收藏 SQL。前端对接：SqlWorkbenchAPI.deleteSavedQuery，由 SqlWorkbench 收藏查询列表调用。"
    )
    @DeleteMapping("/saved-queries/{id}")
    public ApiResponse<Void> deleteSavedQuery(@PathVariable UUID id) {
        service.deleteSavedQuery(id);
        return ApiResponse.ok(null);
    }
}
