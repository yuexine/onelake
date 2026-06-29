package com.onelake.analytics.service;

import com.onelake.analytics.dto.DatasetDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 脱敏策略只读投影（P1 简化版）。
 *
 * 设计原则（§5.2）：脱敏完全下推到 Trino——SqlBuilder 在拼 SELECT 时，
 * 对带脱敏策略的列直接替换为 Trino mask 表达式（如 try_cast(mask_phone(mobile) AS varchar)），
 * 由 Trino 引擎层强制执行，不在 Java 层做行后处理。
 *
 * P1 占位实现：返回空策略（不脱敏）。
 * P3 接入：通过 Outbox 从 module-security 同步脱敏策略到本模块只读投影。
 */
@Component
public class MaskingPolicyView {

    /**
     * 返回当前租户的脱敏策略。
     * key = 字段全名（如 "iceberg.dwd.dwd_user.mobile"），value = mask 表达式 SQL 片段。
     */
    public Map<String, String> forTenant(UUID tenantId) {
        return Collections.emptyMap();
    }

    /**
     * 检查数据集的某个字段是否需要脱敏（用于 Inspector 显示密级标识）。
     */
    public boolean needsMasking(UUID tenantId, String fieldFqn) {
        return forTenant(tenantId).containsKey(fieldFqn);
    }

    /**
     * 数据集字段级别的 mask 策略（基于 field_schema.classification 推导的简化版）。
     * L3/L4 列默认不暴露明文，由 Trino 端通过 RLS / view 实现。
     */
    public List<FieldMasking> forDataset(UUID tenantId, DatasetDTO dataset) {
        // P1 占位：无字段级脱敏
        return Collections.emptyList();
    }

    public record FieldMasking(String field, String expression) {}
}
