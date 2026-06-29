package com.onelake.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * JupyterHub 管理客户端（P4a 启用，P1 占位）。
 *
 * 关键 API（JupyterHub REST API v3）：
 *   GET  /hub/api/users/{name}                -> 查询/创建用户
 *   POST /hub/api/users/{name}/server         -> 启动 named-server
 *   DELETE /hub/api/users/{name}/server       -> 停止 server
 *   GET  /hub/api/users/{name}/servers/{n}/progress  -> 拉启动进度
 *
 * admin-token 是 JupyterHub 的 admin api_token（services.onelake.api_token）。
 */
@Slf4j
@Component
public class JupyterHubClient {

    private final WebClient webClient;
    private final String adminToken;

    public JupyterHubClient(
            @Value("${onelake.dataplane.analytics.jupyterhub.base-url:http://localhost:8000}") String baseUrl,
            @Value("${onelake.dataplane.analytics.jupyterhub.admin-token:}") String adminToken,
            WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.adminToken = adminToken;
    }

    /**
     * 确保用户存在（幂等）。
     */
    public JsonNode ensureUser(String username) {
        return webClient.get()
            .uri("/hub/api/users/{name}", username)
            .header("Authorization", "token " + adminToken)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(5))
            .block();
    }

    /**
     * 启动 named-server（每用户多 notebook 隔离）。
     */
    public void startServer(String username, String serverName, Map<String, Object> options) {
        try {
            webClient.post()
                .uri("/hub/api/users/{user}/servers/{server}", username, serverName)
                .header("Authorization", "token " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("spawn_options", options == null ? Map.of() : options))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        } catch (Exception e) {
            log.error("jupyterhub startServer failed: {}", e.getMessage());
            throw new BizException(50010, "JupyterHub 启动 server 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 返回用户 JupyterLab 入口 URL。
     */
    public String labUrl(String username, String serverName) {
        return webClient.toString().replaceAll("/$", "")
            + "/user/" + username + "/" + serverName + "/lab";
    }
}
