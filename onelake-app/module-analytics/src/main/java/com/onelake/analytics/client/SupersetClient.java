package com.onelake.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Superset REST 客户端（驱动 Superset embedded SDK 的服务端配套）。
 *
 * 关键 API：
 *   POST /api/v1/security/login             -> 服务账号登录，拿 access_token
 *   POST /api/v1/security/guest_token/      -> 为前端签发 guest token（带租户 RLS）
 *   GET  /api/v1/dashboard/{id}             -> 查询 dashboard 元信息（校验数据源含 tenant_id）
 *
 * 前端禁止直接调用 Superset API（避免服务账号泄露）。
 */
@Slf4j
@Component
public class SupersetClient {

    private final WebClient webClient;
    private final String adminUser;
    private final String adminPassword;

    public SupersetClient(
            @Value("${onelake.dataplane.analytics.superset.base-url:http://localhost:8088}") String baseUrl,
            @Value("${onelake.dataplane.analytics.superset.admin-user:admin}") String adminUser,
            @Value("${onelake.dataplane.analytics.superset.admin-password:admin}") String adminPassword,
            WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    /**
     * 服务账号登录，返回 access_token。
     */
    public String login() {
        Map<String, Object> body = Map.of(
            "username", adminUser,
            "password", adminPassword,
            "provider", "db",
            "refresh", true
        );
        try {
            JsonNode resp = webClient.post()
                .uri("/api/v1/security/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
            if (resp == null || !resp.has("access_token")) {
                throw new BizException(50010, "Superset 登录失败：响应无 access_token");
            }
            return resp.get("access_token").asText();
        } catch (Exception e) {
            log.error("superset login failed: {}", e.getMessage());
            throw new BizException(50010, "Superset 登录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 为指定 dashboard 签发 guest token。
     */
    public String createGuestToken(String dashboardUuid, String username,
                                   List<Map<String, String>> rls) {
        String adminToken = login();
        Map<String, Object> payload = Map.of(
            "user", Map.of("username", username == null ? "onelake-guest" : username),
            "resources", List.of(Map.of("type", "dashboard", "id", dashboardUuid)),
            "rls", rls
        );
        try {
            JsonNode resp = webClient.post()
                .uri("/api/v1/security/guest_token/")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
            if (resp == null || !resp.has("token")) {
                throw new BizException(50010, "Superset guest token 签发失败：响应无 token");
            }
            return resp.get("token").asText();
        } catch (Exception e) {
            log.error("superset createGuestToken failed: {}", e.getMessage());
            throw new BizException(50010, "Superset guest token 签发失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取 dashboard 元信息（含 datasets），用于校验数据源含 tenant_id 列。
     */
    public JsonNode fetchDashboard(String dashboardUuid) {
        String adminToken = login();
        return webClient.get()
            .uri("/api/v1/dashboard/{id}", dashboardUuid)
            .header("Authorization", "Bearer " + adminToken)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();
    }
}
