package com.onelake.dataservice.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.dto.SqlApiDebugResultDTO;
import com.onelake.dataservice.service.DataServicePublisher;
import com.onelake.dataservice.service.SqlApiRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dataservice/apis")
@RequiredArgsConstructor
@Tag(name = "数据服务 API", description = "SQL API 草稿、发布、调试、运行时调用和下线接口。")
public class DataServiceController {

    private final DataServicePublisher publisher;
    private final SqlApiRuntimeService runtimeService;

    @Operation(
        summary = "创建 API 草稿",
        description = "用途：基于表或 SQL 创建待发布的数据 API 草稿。前端对接：DataserviceAPI.createDraft，由 ApiWizard 提交流程调用。"
    )
    @PostMapping("/draft")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ApiDefinition> createDraft(@RequestBody ApiDefinition def) {
        return ApiResponse.ok(publisher.createDraft(def));
    }

    @Operation(
        summary = "创建并发布 API",
        description = "用途：直接创建并发布数据 API。前端对接：当前 DataserviceAPI 未封装此直接发布入口，页面使用草稿加 publish 流程。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ApiDefinition> create(@RequestBody ApiDefinition def) {
        return ApiResponse.ok(publisher.publish(def));
    }

    @Operation(
        summary = "发布 API",
        description = "用途：将草稿 API 发布为可调用版本。前端对接：DataserviceAPI.publishApi 已封装，当前页面可在 API 详情/向导扩展发布动作。"
    )
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ApiDefinition> publish(@PathVariable UUID id) {
        ApiDefinition def = publisher.get(id);
        return ApiResponse.ok(publisher.publish(def));
    }

    @Operation(
        summary = "获取 API 详情",
        description = "用途：读取 API 定义、SQL、路径、状态和安全信息。前端对接：DataserviceAPI.getApi，由 ApiDetail 使用。"
    )
    @GetMapping("/{id}")
    public ApiResponse<ApiDefinition> get(@PathVariable UUID id) {
        return ApiResponse.ok(publisher.get(id));
    }

    @Operation(
        summary = "调试 API",
        description = "用途：使用参数预览 SQL API 返回列、行、耗时和脱敏提示。前端对接：DataserviceAPI.debugApi，由 ApiDetail 调试面板调用。"
    )
    @PostMapping("/{id}/debug")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SqlApiDebugResultDTO> debug(
        @PathVariable UUID id,
        @RequestBody(required = false) Map<String, Object> params
    ) {
        return ApiResponse.ok(runtimeService.debug(id, params));
    }

    @Operation(
        summary = "运行时调用数据 API",
        description = "用途：按发布路径和 X-App-Key 执行 SQL API。前端对接：控制台 API 聚合层不直接消费，供外部调用方或网关路由访问。"
    )
    @GetMapping("/runtime/**")
    public ApiResponse<SqlApiDebugResultDTO> invoke(
        HttpServletRequest request,
        @RequestParam Map<String, String> params,
        @RequestHeader(value = "X-App-Key", required = false) String appKey
    ) {
        String apiPath = runtimePath(request);
        Map<String, Object> values = new LinkedHashMap<>(params);
        return ApiResponse.ok(runtimeService.invoke(apiPath, values, appKey, request.getRemoteAddr()));
    }

    @Operation(
        summary = "查询 API 列表",
        description = "用途：返回数据服务 API 市场列表。前端对接：DataserviceAPI.listApis，由 ApiMarket 使用。"
    )
    @GetMapping
    public ApiResponse<List<ApiDefinition>> list() {
        return ApiResponse.ok(publisher.list());
    }

    @Operation(
        summary = "下线 API",
        description = "用途：将已发布 API 标记下线并停止对外服务。前端对接：DataserviceAPI.offlineApi 已封装，ApiDetail 当前有下线确认交互但需接入真实调用。"
    )
    @PostMapping("/{id}/offline")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> offline(@PathVariable UUID id) {
        publisher.offline(id);
        return ApiResponse.ok();
    }

    private String runtimePath(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/v1/dataservice/apis/runtime";
        String uri = request.getRequestURI();
        String path = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }
}
