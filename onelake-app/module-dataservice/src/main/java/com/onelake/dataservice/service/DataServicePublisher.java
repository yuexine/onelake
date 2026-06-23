package com.onelake.dataservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.exception.DataplaneException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final OutboxPublisher outbox;

    @Value("${onelake.dataplane.apisix-admin.base-url:http://localhost:9180/apisix/admin}")
    private String apisixAdminUrl;

    @Value("${onelake.dataplane.apisix-admin.key:edd1c9f034335f136f87ad84b625c8f1}")
    private String apisixAdminKey;

    @Value("${onelake.dataplane.postgrest.endpoint:http://postgrest:3000}")
    private String postgrestEndpoint;

    @Transactional
    public ApiDefinition createDraft(ApiDefinition def) {
        validateDefinition(def);
        def.setStatus("DRAFT");
        def.setTenantId(TenantContext.getTenantId());
        def.setResponseSchema(enrichResponseSchema(def));
        ApiDefinition saved = apiRepo.save(def);
        audit.audit("CREATE_DRAFT", "api", saved.getId().toString(),
            Map.of("path", saved.getApiPath(), "view", saved.getViewName(), "sourceFqn", String.valueOf(saved.getSourceFqn())));
        return saved;
    }

    @Transactional
    public ApiDefinition publish(ApiDefinition def) {
        validateDefinition(def);
        def.setTenantId(TenantContext.getTenantId());
        def.setResponseSchema(enrichResponseSchema(def));
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
        apiRepo.save(def);
        audit.audit("PUBLISH", "api", def.getId().toString(),
            Map.of("path", def.getApiPath(), "view", def.getViewName()));
        outbox.publish(DomainEvents.DATASERVICE_API_PUBLISHED, def.getId().toString(),
            consumerPayload(def, "API", "UPSERT"));
        return def;
    }

    private String enrichResponseSchema(ApiDefinition def) {
        if (def.getTenantId() == null || def.getSourceFqn() == null || def.getSourceFqn().isBlank()) {
            return def.getResponseSchema();
        }
        List<Map<String, Object>> fields = parseResponseSchema(def.getResponseSchema());
        if (fields.isEmpty()) {
            fields = catalogColumns(def.getTenantId(), def.getSourceFqn());
        }
        if (fields.isEmpty()) {
            return def.getResponseSchema();
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            Object name = field.get("name");
            if (name != null && !String.valueOf(name).isBlank()) {
                byName.put(String.valueOf(name).toLowerCase(), field);
            }
        }
        List<Map<String, Object>> terms = jdbc.queryForList("""
            SELECT b.column_name,
                   t.id,
                   t.code,
                   t.name,
                   t.definition,
                   t.caliber_sql,
                   t.sensitivity_level,
                   t.status
            FROM modeling.business_term_binding b
            JOIN modeling.business_term t ON t.id = b.term_id AND t.tenant_id = b.tenant_id
            WHERE b.tenant_id = ?
              AND b.asset_fqn = ?
              AND b.status = 'ACTIVE'
              AND b.column_name IS NOT NULL
            ORDER BY b.column_name, t.code
            """, def.getTenantId(), def.getSourceFqn());
        for (Map<String, Object> term : terms) {
            Object column = term.get("column_name");
            if (column == null) {
                continue;
            }
            Map<String, Object> field = byName.get(String.valueOf(column).toLowerCase());
            if (field == null) {
                continue;
            }
            field.put("termId", String.valueOf(term.get("id")));
            field.put("termCode", term.get("code"));
            field.put("termName", term.get("name"));
            field.put("termDefinition", term.get("definition"));
            field.put("caliberSql", term.get("caliber_sql"));
            field.put("termStatus", term.get("status"));
            String sensitivity = text(term.get("sensitivity_level"));
            if (sensitivity != null && !sensitivity.isBlank()) {
                field.putIfAbsent("classification", sensitivity);
                field.put("suggestLevel", sensitivity);
                if (isSensitive(sensitivity)) {
                    field.put("masked", true);
                }
            }
        }
        return JsonUtil.toJson(fields);
    }

    private List<Map<String, Object>> parseResponseSchema(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> fields = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isObject() || item.path("name").asText("").isBlank()) {
                    continue;
                }
                fields.add(new LinkedHashMap<>(JsonUtil.mapper().convertValue(
                    item,
                    JsonUtil.mapper().getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                )));
            }
            return fields;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> catalogColumns(UUID tenantId, String sourceFqn) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT columns
            FROM catalog.asset
            WHERE tenant_id = ? AND om_fqn = ?
            LIMIT 1
            """, tenantId, sourceFqn);
        if (rows.isEmpty() || rows.get(0).get("columns") == null) {
            return new ArrayList<>();
        }
        String raw = String.valueOf(rows.get(0).get("columns"));
        List<Map<String, Object>> columns = parseResponseSchema(raw);
        for (Map<String, Object> column : columns) {
            String level = firstText(text(column.get("suggestLevel")), text(column.get("classification")));
            if (isSensitive(level)) {
                column.put("masked", true);
            }
        }
        return columns;
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private boolean isSensitive(String level) {
        return "L3".equalsIgnoreCase(level) || "L4".equalsIgnoreCase(level);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void validateDefinition(ApiDefinition def) {
        if (def.getApiPath() == null || def.getApiPath().isBlank()
            || def.getViewName() == null || def.getViewName().isBlank()
            || def.getSelectSql() == null || def.getSelectSql().isBlank()) {
            throw new BizException(40001, "apiPath/viewName/selectSql 必填");
        }
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
        return apiRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
            .orElseThrow(() -> new BizException(40400, "API 不存在"));
    }

    @Transactional
    public void offline(UUID id) {
        ApiDefinition def = get(id);
        jdbc.execute("DROP VIEW IF EXISTS dataservice_api." + def.getViewName());
        def.setStatus("OFFLINE");
        apiRepo.save(def);
        audit.audit("OFFLINE", "api", id.toString(), null);
        outbox.publish(DomainEvents.DATASERVICE_API_OFFLINE, id.toString(),
            consumerPayload(def, "API", "REMOVE"));
    }

    /**
     * 投影事件 payload（对应《血缘图模块完善设计方案》§5.1.2）。
     * 由 catalog 模块的 {@code AssetConsumerEventHandler} 消费，写入 {@code catalog.asset_consumer}。
     */
    private Map<String, Object> consumerPayload(ApiDefinition def, String consumerType, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", String.valueOf(def.getTenantId()));
        payload.put("assetFqn", def.getSourceFqn() == null ? "" : def.getSourceFqn());
        payload.put("consumerType", consumerType);
        payload.put("consumerRef", String.valueOf(def.getId()));
        payload.put("consumerName", def.getApiPath() == null ? def.getViewName() : def.getApiPath());
        payload.put("ownerName", TenantContext.getUsername());
        payload.put("action", action);
        return payload;
    }
}
