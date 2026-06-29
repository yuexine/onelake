package com.onelake.analytics.api;

import com.onelake.analytics.domain.entity.DashboardPublication;
import com.onelake.analytics.service.SharePublishService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.util.JsonUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公开分享 Controller（无鉴权通道）。
 * 路径前缀：/api/v1/analytics/share
 *
 * 仅暴露一个端点：通过 shareToken 拉公开快照（前端在 /share/screen/:token 渲染）。
 * 由 SecurityConfig 的 permitAll 配置放行。
 */
@RestController
@RequestMapping("/api/v1/analytics/share")
@RequiredArgsConstructor
@Tag(name = "数据分析-公开分享", description = "大屏公开分享通道（无鉴权，仅放行 is_public=true 且无 row_filter 的快照）")
public class ShareController {

    private final SharePublishService service;

    @Operation(summary = "通过分享 token 拉取大屏快照")
    @GetMapping("/screen/{token}")
    public ApiResponse<Map<String, Object>> getByToken(@PathVariable String token) {
        DashboardPublication pub = service.getByShareToken(token);
        return ApiResponse.ok(Map.of(
            "snapshot", JsonUtil.parse(pub.getSnapshot()),
            "version", pub.getVersion(),
            "expireAt", String.valueOf(pub.getExpireAt())
        ));
    }
}
