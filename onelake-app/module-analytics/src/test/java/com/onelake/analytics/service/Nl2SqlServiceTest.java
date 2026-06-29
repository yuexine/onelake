package com.onelake.analytics.service;

import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.llm.LlmClient;
import com.onelake.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nl2SqlService 单元测试 —— 覆盖：
 * 1. prompt 构造（含 assetFqn + fieldSchema + question）
 * 2. 围栏剥离（```sql ... ``` → SQL）
 * 3. DML/DDL 安全过滤（拒绝 INSERT/UPDATE/DELETE/DROP）
 * 4. LLM 不可用时抛 BizException
 * 5. 空字段 schema 也能生成
 */
class Nl2SqlServiceTest {

    private LlmClient llm;
    private Nl2SqlService service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        service = new Nl2SqlService(llm);
    }

    @Test
    void buildSystemPrompt_containsTrinoSelectOnlyConstraints() {
        String sys = service.buildSystemPrompt();
        assertThat(sys).contains("Trino");
        assertThat(sys).contains("SELECT");
        assertThat(sys.toLowerCase()).contains("limit");
    }

    @Test
    void buildUserPrompt_includesAllContext() {
        List<DatasetDTO.FieldSchema> fields = List.of(
            DatasetDTO.FieldSchema.builder().name("stat_date").type("date").build(),
            DatasetDTO.FieldSchema.builder().name("gmv").type("decimal").build(),
            DatasetDTO.FieldSchema.builder().name("mobile").type("varchar").classification("L3").build());

        String user = service.buildUserPrompt("iceberg.dwd.dwd_user", fields, "近 30 天 GMV");

        assertThat(user).contains("iceberg.dwd.dwd_user");
        assertThat(user).contains("stat_date");
        assertThat(user).contains("gmv");
        assertThat(user).contains("mobile");
        assertThat(user).contains("[密级 L3]");  // 高密级字段会提示 LLM
        assertThat(user).contains("近 30 天 GMV");
    }

    @Test
    void buildUserPrompt_emptyFields_fallsBackToPlaceholder() {
        String user = service.buildUserPrompt("iceberg.dwd.t", null, "test");
        assertThat(user).contains("(无字段 schema 提示");
    }

    @Test
    void stripCodeFence_removesMarkdownWrappers() {
        assertThat(service.stripCodeFence("```sql\nSELECT 1\n```")).isEqualTo("SELECT 1");
        assertThat(service.stripCodeFence("```\nSELECT 1\n```")).isEqualTo("SELECT 1");
        assertThat(service.stripCodeFence("SELECT 1")).isEqualTo("SELECT 1");
        assertThat(service.stripCodeFence("")).isEqualTo("");
        assertThat(service.stripCodeFence(null)).isEqualTo("");
    }

    @Test
    void generateSql_happyPath_stripsFenceAndReturnsSql() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatCompletion(anyString(), anyString()))
            .thenReturn("```sql\nSELECT stat_date, gmv FROM iceberg.dwd.dwd_user WHERE stat_date >= date '2024-01-01' LIMIT 100\n```");

        String sql = service.generateSql("iceberg.dwd.dwd_user", List.of(), "近 30 天 GMV");

        assertThat(sql).startsWith("SELECT");
        assertThat(sql).contains("iceberg.dwd.dwd_user");
        assertThat(sql).doesNotContain("```");
    }

    @Test
    void generateSql_llmUnavailable_throwsBizException() {
        when(llm.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.generateSql("iceberg.dwd.t", List.of(), "?"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("LLM 不可用");
    }

    @Test
    void generateSql_outputContainsInsert_rejected() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatCompletion(anyString(), anyString()))
            .thenReturn("INSERT INTO foo VALUES (1)");

        assertThatThrownBy(() -> service.generateSql("iceberg.dwd.t", List.of(), "?"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("非 SELECT 操作");
    }

    @Test
    void generateSql_outputContainsDrop_rejected() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatCompletion(anyString(), anyString()))
            .thenReturn("DROP TABLE iceberg.dwd.user");

        assertThatThrownBy(() -> service.generateSql("iceberg.dwd.t", List.of(), "?"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("非 SELECT 操作");
    }

    @Test
    void containsDml_detectsAllDangerousKeywords() {
        assertThat(service.containsDml("SELECT 1")).isFalse();
        assertThat(service.containsDml("INSERT INTO t")).isTrue();
        assertThat(service.containsDml("UPDATE t SET x=1")).isTrue();
        assertThat(service.containsDml("DELETE FROM t")).isTrue();
        assertThat(service.containsDml("DROP TABLE t")).isTrue();
        assertThat(service.containsDml("ALTER TABLE t")).isTrue();
        assertThat(service.containsDml("TRUNCATE t")).isTrue();
        assertThat(service.containsDml("CREATE TABLE t")).isTrue();
        // 大小写不敏感
        assertThat(service.containsDml("select * from t; Insert into x")).isTrue();
    }
}
