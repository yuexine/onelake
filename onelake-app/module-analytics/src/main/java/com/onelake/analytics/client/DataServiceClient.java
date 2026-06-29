package com.onelake.analytics.client;

import com.onelake.analytics.dto.DataBinding;
import com.onelake.analytics.dto.QueryResult;
import com.onelake.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据服务 PostgREST 客户端（source_type=API 的数据集查询通道）。
 *
 * P1 占位实现：PostgREST 已部署在 :3001，由 module-dataservice 维护视图 + APISIX 路由。
 * 本客户端复用其查询入口。
 */
@Slf4j
@Component
public class DataServiceClient {

    private final WebClient webClient;

    public DataServiceClient(
            @Value("${onelake.dataplane.postgrest.endpoint:http://postgrest:3000}") String endpoint,
            WebClient.Builder builder) {
        this.webClient = builder.baseUrl(endpoint).build();
    }

    /**
     * 查询数据服务 API（source_type=API 的数据集）。
     *
     * <p><b>P1 占位实现</b>：实际 dataservice 视图命名是 {@code ApiDefinition.viewName}
     * （由 DataServicePublisher 在 dataservice_api schema 创建）。本客户端暂以 {@code api_{id}}
     * 占位，联调前需要替换为：
     * <ol>
     *   <li>调控制面 {@code GET /api/v1/dataservice/apis/{apiId}} 拿到 {@code viewName}</li>
     *   <li>调 PostgREST {@code /{viewName}?limit=...} 真正查询</li>
     * </ol>
     * 主链路 P1（source_type=ASSET/SQL）不依赖此客户端。
     */
    @SuppressWarnings("unchecked")
    public QueryResult query(UUID apiId, DataBinding binding) {
        // P1 简化：dataservice_api 视图按命名约定 {api_id}_v{version}
        // P2 接入：调控制面 /api/v1/dataservice/apis/{id} 拿 view_name 后再查
        try {
            int limit = binding != null && binding.getLimit() != null ? binding.getLimit() : 10_000;
            List<Map<String, Object>> rows = webClient.get()
                .uri("/dataservice_api/api_{id}?limit={limit}", apiId, Math.min(limit, 100_000))
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            return QueryResult.of((List<Map<String, Object>>) rows, List.of());
        } catch (Exception e) {
            log.error("dataservice query failed: {}", e.getMessage());
            throw new BizException(50010, "数据服务查询失败：" + e.getMessage(), e);
        }
    }
}
