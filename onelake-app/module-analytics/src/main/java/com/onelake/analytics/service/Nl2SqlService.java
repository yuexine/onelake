package com.onelake.analytics.service;

import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.llm.LlmClient;
import com.onelake.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NL2SQL 服务（P5-C 智能建数据集）。
 *
 * 关键设计：
 * 1. prompt 把数据集 schema（字段名 + 类型 + 密级）作为 context，提高 SQL 准确率
 * 2. 限制只生成 SELECT，禁止 INSERT/UPDATE/DELETE/DROP（前置校验）
 * 3. 输出 SQL 自动剥离 markdown ``` 围栏
 * 4. LLM 不可用时返回友好错误（不阻塞 UI）
 *
 * 适用场景：
 * - 用户输入"华东最近 30 天 GMV 趋势" → 生成 SELECT stat_date, gmv FROM ... WHERE region='华东' ...
 * - 数据集来源 asset_fqn 已知，LLM 只生成 SELECT 子句的 WHERE / GROUP BY / ORDER BY
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlService {

    private final LlmClient llm;

    private static final Pattern DDL_PATTERN = Pattern.compile(
        "\\b(insert|update|delete|drop|alter|truncate|create|merge|grant|revoke)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE = Pattern.compile("```(sql)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 根据数据集 + 自然语言问题生成 Trino SQL。
     *
     * @param assetFqn     数据集关联的 Iceberg 表 FQN（必填）
     * @param fieldSchema  字段 schema（提供 LLM 作为 context）
     * @param question     用户的自然语言问题
     * @return 推荐的 Trino SQL（仅 SELECT 语句）
     */
    public String generateSql(String assetFqn, List<DatasetDTO.FieldSchema> fieldSchema, String question) {
        if (!llm.isAvailable()) {
            throw new BizException(50020,
                "LLM 不可用（" + llm.provider() + "）。请联系管理员配置 API key。");
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(assetFqn, fieldSchema, question);

        String raw = llm.chatCompletion(systemPrompt, userPrompt);
        String sql = stripCodeFence(raw).trim();

        // 安全：拒绝任何 DDL/DML 关键字
        if (containsDml(sql)) {
            log.warn("NL2SQL output rejected for safety, question={}: {}", question, sql);
            throw new BizException(50021,
                "生成的 SQL 含非 SELECT 操作，已被安全策略拒绝。请重新描述你的查询需求。");
        }

        log.info("NL2SQL generated: asset={} question='{}' -> sql_len={}", assetFqn, question, sql.length());
        return sql;
    }

    // ============ prompt 构造 ============

    String buildSystemPrompt() {
        return """
            你是 Trino SQL 专家。任务：根据用户提供的 Iceberg 表 FQN、字段 schema 和自然语言问题，
            生成一条可直接执行的 Trino SELECT 语句。

            严格规则：
            1. 只输出 SELECT 语句本身，禁止解释、禁止 markdown 围栏、禁止注释
            2. 时间字段按 stat_date / created_at 等常见命名直接使用
            3. 字段引用必须用双引号包裹（Trino 标识符规则），字符串字面量用单引号
            4. 聚合函数：SUM / COUNT / AVG / MAX / MIN
            5. 时间窗口：使用 Trino date_add/date_diff 或 interval 表达式
            6. 若用户的问题无法基于给定字段回答，输出 "SELECT 1 -- 无法回答" 并简短说明
            7. 默认 LIMIT 100，除非用户明确要全部
            8. 禁止 INSERT / UPDATE / DELETE / DROP / ALTER / CREATE / TRUNCATE / MERGE
            """;
    }

    String buildUserPrompt(String assetFqn, List<DatasetDTO.FieldSchema> fieldSchema, String question) {
        String fields = fieldSchema == null || fieldSchema.isEmpty()
            ? "(无字段 schema 提示，按命名约定推测)"
            : fieldSchema.stream()
                .map(f -> "  - " + f.getName() + " (" + f.getType() + ")"
                    + (f.getClassification() != null && !"L1".equals(f.getClassification())
                        ? " [密级 " + f.getClassification() + "]" : ""))
                .collect(Collectors.joining("\n"));

        return """
            目标表（Iceberg FQN）：%s

            可用字段：
            %s

            用户问题：%s

            生成 Trino SELECT：
            """.formatted(assetFqn, fields, question);
    }

    // ============ SQL 后处理 ============

    String stripCodeFence(String raw) {
        if (raw == null) return "";
        Matcher m = CODE_FENCE.matcher(raw);
        if (m.find()) {
            return m.group(2).trim();
        }
        return raw.trim();
    }

    boolean containsDml(String sql) {
        if (sql == null || sql.isBlank()) return false;
        // 排除 SELECT 内的字符串字面量（简化：粗略匹配关键字）
        Matcher m = DDL_PATTERN.matcher(sql);
        // 跳过 SELECT 内 sub-query 的 insert 等关键字（极罕见），简化策略：直接匹配
        return m.find();
    }
}
