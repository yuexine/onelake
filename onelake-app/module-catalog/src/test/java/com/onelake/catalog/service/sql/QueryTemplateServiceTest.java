package com.onelake.catalog.service.sql;

import com.onelake.catalog.domain.entity.sql.QueryTemplate;
import com.onelake.catalog.dto.sql.QueryTemplateDTO;
import com.onelake.catalog.dto.sql.QueryTemplateRenderRequest;
import com.onelake.catalog.dto.sql.QueryTemplateRenderResultDTO;
import com.onelake.catalog.dto.sql.QueryTemplateSaveRequest;
import com.onelake.catalog.repository.sql.QueryTemplateRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.service.AclService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryTemplateServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private QueryTemplateRepository templateRepo;
    private AuditLogger auditLogger;
    private AclService aclService;
    private QueryTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepo = mock(QueryTemplateRepository.class);
        auditLogger = mock(AuditLogger.class);
        aclService = mock(AclService.class);
        service = new QueryTemplateService(templateRepo, auditLogger, aclService);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("dev");
        when(templateRepo.save(any(QueryTemplate.class))).thenAnswer(invocation -> {
            QueryTemplate t = invocation.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            }
            return t;
        });
        // ACL mock 默认允许：让 ACL 相关校验放行；具体行为在 ACL 单测里覆盖
        org.mockito.Mockito.doNothing().when(aclService).requireEdit(any(), any(), any());
        org.mockito.Mockito.doNothing().when(aclService).autoGrantOnShared(any(), any());
        org.mockito.Mockito.doNothing().when(aclService).autoRevokeOnPrivate(any(), any());
        org.mockito.Mockito.doNothing().when(aclService).cleanupOnDelete(any(), any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void saveTemplateStoresSqlAndPlaceholders() {
        when(templateRepo.findByTenantIdAndName(any(), anyString())).thenReturn(Optional.empty());
        var req = new QueryTemplateSaveRequest(
            "日订单分析",
            "销售",
            "按日聚合订单量",
            "SELECT dt, count(*) AS cnt FROM ods.orders WHERE dt = '{{dt}}' GROUP BY dt",
            List.of(new QueryTemplateDTO.PlaceholderSpec("dt", "date", true, null, "业务日期")),
            true
        );

        QueryTemplateDTO saved = service.saveTemplate(req);

        assertThat(saved.name()).isEqualTo("日订单分析");
        assertThat(saved.placeholders()).hasSize(1);
        assertThat(saved.placeholders().get(0).name()).isEqualTo("dt");
        assertThat(saved.sqlTemplate()).contains("{{dt}}");
    }

    @Test
    void saveTemplateRejectsUndeclaredPlaceholderInSql() {
        var req = new QueryTemplateSaveRequest(
            "有未声明占位符的模板",
            null, null,
            "SELECT * FROM ods.orders WHERE dt = '{{dt}}' AND user = '{{user_id}}'",
            List.of(new QueryTemplateDTO.PlaceholderSpec("dt", "date", true, null, null)),
            false
        );

        assertThatThrownBy(() -> service.saveTemplate(req))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("user_id");
    }

    @Test
    void renderEscapesSingleQuoteForStringType() {
        QueryTemplate tpl = template("日订单", "SELECT * FROM ods.users WHERE name = '{{name}}'",
            List.of(placeholder("name", "string", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        QueryTemplateRenderResultDTO result = service.renderTemplate(tpl.getId(),
            new QueryTemplateRenderRequest(Map.of("name", "O'Brien")));

        assertThat(result.sql()).contains("name = 'O''Brien'");
        assertThat(result.sql()).doesNotContain("O'Brien'");
    }

    @Test
    void renderRejectsNonNumericForNumberType() {
        QueryTemplate tpl = template("按 id", "SELECT * FROM ods.orders WHERE id = {{order_id}}",
            List.of(placeholder("order_id", "number", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.renderTemplate(tpl.getId(),
                new QueryTemplateRenderRequest(Map.of("order_id", "1; DROP TABLE"))))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                assertThat(((BizException) err).getCode()).isEqualTo(40051);
            });
    }

    @Test
    void renderRejectsInvalidIdentifier() {
        QueryTemplate tpl = template("按表名", "SELECT * FROM {{table_name}} LIMIT 10",
            List.of(placeholder("table_name", "identifier", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.renderTemplate(tpl.getId(),
                new QueryTemplateRenderRequest(Map.of("table_name", "ods.x; DROP TABLE ods.orders"))))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                assertThat(((BizException) err).getCode()).isEqualTo(40051);
            });
    }

    @Test
    void renderRejectsMissingRequiredPlaceholder() {
        QueryTemplate tpl = template("需要 dt", "SELECT * FROM ods.orders WHERE dt = '{{dt}}'",
            List.of(placeholder("dt", "date", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.renderTemplate(tpl.getId(),
                new QueryTemplateRenderRequest(Map.of())))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("dt");
    }

    @Test
    void renderFallsBackToDefaultWhenValueMissing() {
        QueryTemplate tpl = template("有默认值", "SELECT * FROM ods.orders WHERE dt = '{{dt}}'",
            List.of(placeholder("dt", "date", true, "2026-01-01")));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        QueryTemplateRenderResultDTO result = service.renderTemplate(tpl.getId(),
            new QueryTemplateRenderRequest(Map.of()));

        assertThat(result.sql()).contains("2026-01-01");
        assertThat(result.replacedCount()).isEqualTo(1);
    }

    @Test
    void renderRejectsBooleanTypeWithInvalidValue() {
        QueryTemplate tpl = template("按 flag", "SELECT * FROM ods.orders WHERE is_paid = {{paid}}",
            List.of(placeholder("paid", "boolean", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.renderTemplate(tpl.getId(),
                new QueryTemplateRenderRequest(Map.of("paid", "yes OR 1=1"))))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                assertThat(((BizException) err).getCode()).isEqualTo(40051);
            });
    }

    @Test
    void renderAcceptsValidBoolean() {
        QueryTemplate tpl = template("按 flag", "SELECT * FROM ods.orders WHERE is_paid = {{paid}}",
            List.of(placeholder("paid", "boolean", true, null)));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.of(tpl));

        QueryTemplateRenderResultDTO result = service.renderTemplate(tpl.getId(),
            new QueryTemplateRenderRequest(Map.of("paid", "true")));

        assertThat(result.sql()).contains("is_paid = true");
    }

    private QueryTemplate template(String name, String sql, List<QueryTemplateDTO.PlaceholderSpec> specs) {
        QueryTemplate tpl = new QueryTemplate();
        tpl.setId(UUID.randomUUID());
        tpl.setTenantId(TENANT_ID);
        tpl.setName(name);
        tpl.setSqlTemplate(sql);
        tpl.setPlaceholders(toJson(specs));
        tpl.setCreatedAt(Instant.now());
        tpl.setUpdatedAt(Instant.now());
        return tpl;
    }

    private QueryTemplateDTO.PlaceholderSpec placeholder(String name, String type, boolean required, String defaultValue) {
        return new QueryTemplateDTO.PlaceholderSpec(name, type, required, defaultValue, null);
    }

    private String toJson(List<QueryTemplateDTO.PlaceholderSpec> specs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) sb.append(",");
            QueryTemplateDTO.PlaceholderSpec s = specs.get(i);
            sb.append("{\"name\":\"").append(s.name()).append("\"");
            sb.append(",\"type\":\"").append(s.type()).append("\"");
            sb.append(",\"required\":").append(s.required());
            if (s.defaultValue() != null) sb.append(",\"default\":\"").append(s.defaultValue()).append("\"");
            sb.append("}");
        }
        return sb.append("]").toString();
    }
}
