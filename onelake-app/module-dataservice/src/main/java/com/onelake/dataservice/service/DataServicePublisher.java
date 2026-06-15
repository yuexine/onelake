package com.onelake.dataservice.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.exception.DataplaneException;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据服务发布器（对应《技术初始化文档》§6.6）。
 * 1) 在 dataservice_api schema 暴露只读视图；2) 注册 APISIX 路由 + AppKey 鉴权 + 限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataServicePublisher {

    private final JdbcTemplate jdbc;
    private final WebClient.Builder webClientBuilder;
    private final ApiDefinitionRepository apiRepo;
    private final AuditLogger audit;

    @Value("${onelake.dataplane.apisix-admin.base-url:http://localhost:9180/apisix/admin}")
    private String apisixAdminUrl;

    @Value("${onelake.dataplane.apisix-admin.key:edd1c9f034335f136f87ad84b625c8f1}")
    private String apisixAdminKey;

    @Value("${onelake.dataplane.postgrest.endpoint:http://postgrest:3000}")
    private String postgrestEndpoint;

    @Transactional
    public ApiDefinition publish(ApiDefinition def) {
        if (def.getViewName() == null || def.getSelectSql() == null) {
            throw new BizException(40001, "viewName/selectSql 必填");
        }
        // 1) 物化/暴露视图（PostgREST 会据此自动出 REST 端点）
        jdbc.execute("CREATE OR REPLACE VIEW dataservice_api." + def.getViewName() + " AS " + def.getSelectSql());
        jdbc.execute("GRANT SELECT ON dataservice_api." + def.getViewName() + " TO web_anon");

        // 2) 注册 APISIX 路由
        try {
            registerRoute(def);
        } catch (Exception e) {
            log.warn("apisix route register skipped: {}", e.getMessage());
        }

        def.setStatus("PUBLISHED");
        def.setTenantId(TenantContext.getTenantId());
        apiRepo.save(def);
        audit.audit("PUBLISH", "api", def.getId().toString(),
            Map.of("path", def.getApiPath(), "view", def.getViewName()));
        return def;
    }

    private void registerRoute(ApiDefinition def) {
        WebClient client = webClientBuilder
            .baseUrl(apisixAdminUrl)
            .defaultHeader("X-API-KEY", apisixAdminKey)
            .build();

        Map<String, Object> route = Map.of(
            "uri", "/api/" + def.getApiPath(),
            "upstream", Map.of("type", "roundrobin", "nodes",
                Map.of(postgrestEndpoint.replaceFirst("^http://", ""), 1)),
            "plugins", Map.of(
                "proxy-rewrite", Map.of("uri", "/" + def.getViewName()),
                "key-auth", Map.of(),
                "limit-req", Map.of("rate", def.getQpsLimit(), "burst", def.getQpsLimit(),
                    "key", "remote_addr", "rejected_code", 429))
        );

        client.put().uri("/routes/" + def.getId())
            .bodyValue(route)
            .retrieve()
            .toBodilessEntity()
            .block();
    }

    @Transactional(readOnly = true)
    public List<ApiDefinition> list() {
        return apiRepo.findByTenantId(TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public ApiDefinition get(UUID id) {
        return apiRepo.findById(id).orElseThrow(() -> new BizException(40400, "API 不存在"));
    }

    @Transactional
    public void offline(UUID id) {
        ApiDefinition def = get(id);
        jdbc.execute("DROP VIEW IF EXISTS dataservice_api." + def.getViewName());
        def.setStatus("OFFLINE");
        audit.audit("OFFLINE", "api", id.toString(), null);
    }
}
