package com.onelake.analytics.api;

import com.onelake.analytics.service.SupersetEmbedService;
import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Superset 嵌入 Controller。
 * 路径前缀：/api/v1/analytics/superset
 *
 * 仅一个端点：签发 guest token。
 * 前端拿到 token 后通过 @superset-ui/embedded-sdk 在浏览器内 iframe 渲染。
 */
@RestController
@RequestMapping("/api/v1/analytics/superset")
@RequiredArgsConstructor
@Tag(name = "数据分析-Superset 嵌入", description = "Superset guest token 签发（前端不直接调 Superset）")
public class SupersetEmbedController {

    private final SupersetEmbedService service;

    @Operation(summary = "签发 Superset 嵌入 guest token（带租户 RLS）")
    @PostMapping("/guest-token")
    @PreAuthorize("hasAnyRole('DE','ANALYST','CONSUMER')")
    public ApiResponse<Map<String, String>> guestToken(@RequestBody Map<String, String> body) {
        String uuid = body.get("uuid");
        if (uuid == null || uuid.isBlank()) {
            return ApiResponse.fail(40000, "uuid 必填");
        }
        String token = service.guestToken(uuid);
        return ApiResponse.ok(Map.of("token", token));
    }
}
