package com.onelake.analytics.api;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.entity.Notebook;
import com.onelake.analytics.repository.NotebookRepository;
import com.onelake.analytics.service.NotebookArtifactService;
import com.onelake.analytics.service.NotebookRunService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Notebook Controller（P4a-d 启用）。
 *
 * 端点：
 *   POST /api/v1/analytics/notebooks                -> 创建 notebook（占位 .ipynb 落 MinIO）
 *   GET  /api/v1/analytics/notebooks                -> 列出当前租户
 *   GET  /api/v1/analytics/notebooks/{id}/lab-url   -> 返回 JupyterHub 入口
 *   POST /api/v1/analytics/notebooks/issue-token    -> pre_spawn_hook 调用（签发短期 token）
 *   POST /api/v1/analytics/notebooks/{id}/runs      -> 提交 Dagster 调度（P4c）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics/notebooks")
@RequiredArgsConstructor
@Tag(name = "数据分析-Notebook", description = "JupyterHub Notebook 工作台 + papermill 调度")
public class NotebookController {

    private final NotebookRepository notebookRepo;
    private final NotebookRunService runService;
    private final NotebookArtifactService artifactService;

    @Value("${onelake.dataplane.analytics.jupyterhub.base-url:http://localhost:8000}")
    private String jupyterBaseUrl;

    @Value("${onelake.dataplane.analytics.jupyterhub.notebook-token-ttl-seconds:3600}")
    private long tokenTtl;

    /**
     * pre_spawn_hook 调用：为 Notebook 注入短期 token。
     *
     * 关键（§7.11）：
     * - 服务端用 Keycloak 用户 JWT 反查 tenant / username
     * - 镜像不存长期密钥，token 由 spawn 时动态下发
     */
    @PostMapping("/issue-token")
    public ApiResponse<Map<String, Object>> issueToken(@RequestBody Map<String, String> body) {
        UUID tenant = TenantContext.getTenantId();
        String username = TenantContext.getUsername();
        String notebookId = body.get("notebook_id");

        // P4a 简化：直接复用当前 JWT 作为 ONELAKE_TOKEN（与 Keycloak 同源）。
        // P4d 接入：可换成独立签名（HS256 with GUEST_TOKEN_JWT_SECRET）。
        String token = "user-jwt";  // 占位：实际由 pre_spawn_hook 用 auth_state.access_token 替代

        log.info("issue-token for tenant={} user={} notebook={}", tenant, username, notebookId);
        return ApiResponse.ok(Map.of(
            "token", token,
            "tenant_id", String.valueOf(tenant),
            "notebook_id", notebookId == null ? "" : notebookId,
            "expires_in", tokenTtl
        ));
    }

    @Operation(summary = "获取 Notebook JupyterLab 入口 URL")
    @GetMapping("/{id}/lab-url")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, String>> labUrl(@PathVariable UUID id, @RequestParam(defaultValue = "default") String serverName) {
        Notebook nb = notebookRepo.findByIdAndTenantId(id, TenantContext.getTenantId())
            .orElseThrow(() -> new BizException(40400, "Notebook 不存在"));
        String username = TenantContext.getUsername();
        String url = String.format("%s/user/%s/%s/lab?token=onelake-auto", jupyterBaseUrl, username, serverName);
        return ApiResponse.ok(Map.of("url", url, "notebook_id", String.valueOf(nb.getId())));
    }

    @Operation(summary = "提交 Notebook 调度运行（papermill via Dagster）")
    @PostMapping("/{id}/runs")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, Object>> scheduleRun(@PathVariable UUID id,
                                                        @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.ok(Map.of(
            "run", runService.schedule(id, params == null ? Map.of() : params)
        ));
    }

    @Operation(summary = "注册 Notebook 产出资产（onelake.publish() 调用）")
    @PostMapping("/artifact")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Dataset> registerArtifact(@RequestBody Map<String, String> body) {
        UUID notebookId = body.get("produced_by_notebook") != null && !body.get("produced_by_notebook").isBlank()
            ? UUID.fromString(body.get("produced_by_notebook")) : null;
        Dataset ds = artifactService.register(
            body.get("fqn"),
            body.get("classification"),
            body.get("description"),
            notebookId
        );
        return ApiResponse.ok(ds);
    }
}
