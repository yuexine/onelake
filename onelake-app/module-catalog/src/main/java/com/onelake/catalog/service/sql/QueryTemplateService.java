package com.onelake.catalog.service.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.domain.entity.sql.QueryTemplate;
import com.onelake.catalog.dto.sql.QueryTemplateDTO;
import com.onelake.catalog.dto.sql.QueryTemplateRenderRequest;
import com.onelake.catalog.dto.sql.QueryTemplateRenderResultDTO;
import com.onelake.catalog.dto.sql.QueryTemplateSaveRequest;
import com.onelake.catalog.repository.sql.QueryTemplateRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.sql.ReadOnlySqlValidator;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.service.AclService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 查询模板服务（Sprint 5a）。
 *
 * 设计要点：
 *   1. 占位符语法 {{name}}，不带类型标注；类型从 placeholders JSON 元数据读取
 *   2. 渲染时按类型转义，防止 SQL 注入：
 *      - string/date/timestamp: 单引号 → 两个单引号（SQL 标准）
 *      - number: 校验数字格式
 *      - boolean: 仅允许 true/false
 *      - identifier: 仅允许 [a-zA-Z_][a-zA-Z0-9_.]*
 *   3. 模板中的 {{xxx}} 必须在 placeholders 中声明；未声明的占位符视为错误
 *   4. 渲染后强制走 ReadOnlySqlValidator，双重保险
 */
@Service
@RequiredArgsConstructor
public class QueryTemplateService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    private final QueryTemplateRepository templateRepo;
    private final AuditLogger auditLogger;
    private final AclService aclService;

    @Transactional(readOnly = true)
    public List<QueryTemplateDTO> listTemplates() {
        UUID tenantId = TenantContext.getTenantId();
        List<QueryTemplate> all = templateRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        List<QueryTemplate> viewable = aclService.filterViewable(all, AclService.RESOURCE_QUERY_TEMPLATE, new AclService.ResourceAccessor<QueryTemplate>() {
            @Override public UUID ownerId(QueryTemplate r) { return r.getOwnerId(); }
            @Override public UUID resourceId(QueryTemplate r) { return r.getId(); }
        });
        return viewable.stream().map(this::toDTO).toList();
    }

    @Transactional
    public QueryTemplateDTO saveTemplate(QueryTemplateSaveRequest request) {
        String name = validateName(request.name());
        String sqlTemplate = validateSqlTemplate(request.sqlTemplate());
        List<QueryTemplateDTO.PlaceholderSpec> specs = normalizeSpecs(request.placeholders(), sqlTemplate);
        UUID tenantId = TenantContext.getTenantId();
        QueryTemplate template = templateRepo.findByTenantIdAndName(tenantId, name)
            .orElseGet(QueryTemplate::new);
        Instant now = Instant.now();
        boolean created = template.getId() == null;
        boolean wasShared = !created && template.isShared();
        boolean nowShared = request.shared();
        if (!created) {
            aclService.requireEdit(AclService.RESOURCE_QUERY_TEMPLATE, template.getId(), template.getOwnerId());
        }
        if (created) {
            template.setTenantId(tenantId);
            template.setOwnerId(TenantContext.getUserId());
            template.setOwnerName(runnerName());
            template.setCreatedAt(now);
        }
        template.setName(name);
        template.setCategory(request.category());
        template.setDescription(request.description());
        template.setSqlTemplate(sqlTemplate);
        template.setPlaceholders(JsonUtil.toJson(specs));
        template.setShared(request.shared());
        template.setUpdatedAt(now);
        QueryTemplateDTO dto = toDTO(templateRepo.save(template));
        if (nowShared && !wasShared) {
            aclService.autoGrantOnShared(AclService.RESOURCE_QUERY_TEMPLATE, template.getId());
        } else if (!nowShared && wasShared) {
            aclService.autoRevokeOnPrivate(AclService.RESOURCE_QUERY_TEMPLATE, template.getId());
        }
        if (created) {
            auditLogger.auditCreate("QueryTemplate", dto.id(), auditDetail(dto));
        } else {
            auditLogger.auditUpdate("QueryTemplate", dto.id(), auditDetail(dto));
        }
        return dto;
    }

    @Transactional
    public QueryTemplateDTO updateTemplate(UUID id, QueryTemplateSaveRequest request) {
        String name = validateName(request.name());
        String sqlTemplate = validateSqlTemplate(request.sqlTemplate());
        List<QueryTemplateDTO.PlaceholderSpec> specs = normalizeSpecs(request.placeholders(), sqlTemplate);
        UUID tenantId = TenantContext.getTenantId();
        QueryTemplate template = templateRepo.findByTenantIdAndId(tenantId, id)
            .orElseThrow(() -> new BizException(40406, "查询模板不存在"));
        aclService.requireEdit(AclService.RESOURCE_QUERY_TEMPLATE, id, template.getOwnerId());
        boolean wasShared = template.isShared();
        boolean nowShared = request.shared();
        template.setName(name);
        template.setCategory(request.category());
        template.setDescription(request.description());
        template.setSqlTemplate(sqlTemplate);
        template.setPlaceholders(JsonUtil.toJson(specs));
        template.setShared(request.shared());
        template.setUpdatedAt(Instant.now());
        QueryTemplateDTO dto = toDTO(templateRepo.save(template));
        if (nowShared && !wasShared) {
            aclService.autoGrantOnShared(AclService.RESOURCE_QUERY_TEMPLATE, id);
        } else if (!nowShared && wasShared) {
            aclService.autoRevokeOnPrivate(AclService.RESOURCE_QUERY_TEMPLATE, id);
        }
        auditLogger.auditUpdate("QueryTemplate", id, auditDetail(dto));
        return dto;
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        QueryTemplate template = templateRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
            .orElseThrow(() -> new BizException(40406, "查询模板不存在"));
        aclService.requireEdit(AclService.RESOURCE_QUERY_TEMPLATE, id, template.getOwnerId());
        templateRepo.delete(template);
        aclService.cleanupOnDelete(AclService.RESOURCE_QUERY_TEMPLATE, id);
        auditLogger.auditDelete("QueryTemplate", id);
    }

    /**
     * 渲染模板：替换占位符 + 强制只读校验。
     *
     * @throws BizException 40406 模板不存在
     * @throws BizException 40051 占位符未声明 / 必填缺失 / 类型不匹配
     * @throws BizException 40040 渲染后 SQL 非只读
     */
    public QueryTemplateRenderResultDTO renderTemplate(UUID id, QueryTemplateRenderRequest request) {
        QueryTemplate template = templateRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
            .orElseThrow(() -> new BizException(40406, "查询模板不存在"));

        Map<String, QueryTemplateDTO.PlaceholderSpec> specByName = new LinkedHashMap<>();
        for (QueryTemplateDTO.PlaceholderSpec spec : parseSpecs(template.getPlaceholders())) {
            specByName.put(spec.name().toLowerCase(Locale.ROOT), spec);
        }

        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template.getSqlTemplate());
        StringBuffer rendered = new StringBuffer();
        int replaced = 0;
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            QueryTemplateDTO.PlaceholderSpec spec = specByName.get(key);
            if (spec == null) {
                throw new BizException(40051, "SQL 模板引用了未声明的占位符: {{ " + matcher.group(1) + " }}");
            }
            String raw = values.get(spec.name());
            if (raw == null || raw.isEmpty()) {
                if (spec.defaultValue() != null && !spec.defaultValue().isEmpty()) {
                    raw = spec.defaultValue();
                } else if (spec.required()) {
                    throw new BizException(40051, "必填占位符缺失: " + spec.name());
                } else {
                    raw = "";
                }
            }
            String escaped = escapeByType(spec.name(), raw, spec.type());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(escaped));
            replaced++;
        }
        matcher.appendTail(rendered);

        String sql = rendered.toString().trim();
        // 渲染后强制只读校验，防止模板把 DELETE/WRITE 伪装成 SELECT
        ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            sql, 40040, "渲染后的 SQL 必须是只读", "渲染后的 SQL 不得包含多条语句"
        );

        auditLogger.audit("RENDER", "QueryTemplate", id.toString(), auditDetail(
            "name", template.getName(),
            "replacedCount", replaced,
            "sqlLength", sql.length()
        ));
        return new QueryTemplateRenderResultDTO(sql, replaced, false);
    }

    private String escapeByType(String name, String value, String type) {
        if (value == null) return "";
        String t = type == null ? "string" : type.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "number" -> {
                if (!NUMBER_PATTERN.matcher(value).matches()) {
                    throw new BizException(40051, "占位符 " + name + " 期望 number，实际收到: " + truncate(value));
                }
                yield value;
            }
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BizException(40051, "占位符 " + name + " 期望 boolean (true/false)，实际收到: " + truncate(value));
                }
                yield value;
            }
            case "identifier" -> {
                if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
                    throw new BizException(40051, "占位符 " + name + " 期望 identifier（仅字母/数字/下划线/点），实际收到: " + truncate(value));
                }
                yield value;
            }
            case "string", "date", "timestamp" -> escapeSqlString(value);
            default -> throw new BizException(40051, "占位符 " + name + " 声明了未知类型: " + type);
        };
    }

    private static String escapeSqlString(String value) {
        // SQL 标准转义：单引号 → 两个单引号
        return value.replace("'", "''");
    }

    private static String truncate(String value) {
        return value.length() <= 50 ? value : value.substring(0, 50) + "...";
    }

    private List<QueryTemplateDTO.PlaceholderSpec> normalizeSpecs(
        List<QueryTemplateDTO.PlaceholderSpec> specs,
        String sqlTemplate
    ) {
        if (specs == null) specs = List.of();
        Map<String, QueryTemplateDTO.PlaceholderSpec> byName = new LinkedHashMap<>();
        for (QueryTemplateDTO.PlaceholderSpec spec : specs) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw new BizException(40051, "占位符 name 不能为空");
            }
            byName.put(spec.name().toLowerCase(Locale.ROOT), spec);
        }
        // 校验 SQL 中引用的占位符都已声明
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!byName.containsKey(key)) {
                throw new BizException(40051, "SQL 引用了未声明的占位符: {{ " + matcher.group(1) + " }}");
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<QueryTemplateDTO.PlaceholderSpec> parseSpecs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = JsonUtil.parse(json);
            if (!node.isArray()) return List.of();
            List<QueryTemplateDTO.PlaceholderSpec> result = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                result.add(new QueryTemplateDTO.PlaceholderSpec(
                    item.path("name").asText(null),
                    item.path("type").asText("string"),
                    item.path("required").asBoolean(false),
                    item.path("default").asText(null),
                    item.path("description").asText(null)
                ));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(40000, "模板名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new BizException(40041, "模板名称不能超过 128 个字符");
        }
        return trimmed;
    }

    private String validateSqlTemplate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(40000, "SQL 模板不能为空");
        }
        String trimmed = sql.trim();
        if (trimmed.length() > 100_000) {
            throw new BizException(40043, "SQL 模板长度不能超过 100000 字符");
        }
        return trimmed;
    }

    private QueryTemplateDTO toDTO(QueryTemplate template) {
        return new QueryTemplateDTO(
            template.getId(),
            template.getName(),
            template.getCategory(),
            template.getDescription(),
            template.getSqlTemplate(),
            parseSpecs(template.getPlaceholders()),
            template.getOwnerId(),
            template.getOwnerName(),
            template.isShared(),
            template.getUpdatedAt()
        );
    }

    private String runnerName() {
        String username = TenantContext.getUsername();
        return username == null || username.isBlank() ? "unknown" : username;
    }

    private static Map<String, Object> auditDetail(QueryTemplateDTO dto) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", dto.name());
        detail.put("category", dto.category());
        detail.put("shared", dto.shared());
        detail.put("placeholderCount", dto.placeholders() == null ? 0 : dto.placeholders().size());
        return detail;
    }

    private static Map<String, Object> auditDetail(Object... kv) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            detail.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return detail;
    }
}
