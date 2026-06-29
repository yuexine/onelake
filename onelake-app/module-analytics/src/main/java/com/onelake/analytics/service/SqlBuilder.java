package com.onelake.analytics.service;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.dto.DataBinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQL 构造器：把 Dataset + DataBinding + 脱敏策略组合成 Trino SQL。
 *
 * 关键设计（§5.2、§7.6）：
 * 1. 脱敏完全下推到 Trino——在 SELECT 阶段把敏感列替换为 mask 表达式。
 * 2. 行级过滤（row_filter）注入 WHERE 子句。
 * 3. 聚合/筛选/排序/分页按 DataBinding 组装。
 *
 * 这是控制面与 Trino 之间的唯一 SQL 出口，禁止其他地方拼接 SQL。
 */
@Component
public class SqlBuilder {

    public String compose(Dataset ds, DataBinding binding, Map<String, String> masking) {
        String baseSql = baseSelect(ds);
        StringBuilder sb = new StringBuilder("SELECT ");

        boolean hasAgg = binding != null
            && binding.getMeasures() != null
            && !binding.getMeasures().isEmpty();

        if (!hasAgg) {
            // 无聚合：SELECT *（Trino 引擎推 limit 下推优化由后端控制）
            sb.append("*");
        } else {
            // 聚合：dim1, dim2, agg(mask(measure1)) AS measure1
            List<String> proj = new ArrayList<>();
            if (binding.getDimensions() != null) {
                binding.getDimensions().forEach(dim -> proj.add(quoteIdent(dim)));
            }
            binding.getMeasures().forEach(m -> {
                String field = quoteIdent(m.getField());
                String bare = m.getField();
                String inner = masking.containsKey(bare) ? masking.get(bare) : field;
                String agg = m.getAgg() == null ? "sum" : m.getAgg().toLowerCase();
                proj.add(agg + "(" + inner + ") AS " + quoteIdent(m.getField()));
            });
            sb.append(String.join(", ", proj));
        }

        sb.append(" FROM (").append(baseSql).append(") t");

        // WHERE：数据集 row_filter + 绑定 filters
        List<String> whereParts = new ArrayList<>();
        if (ds.getRowFilter() != null && !ds.getRowFilter().isBlank()) {
            whereParts.add("(" + ds.getRowFilter() + ")");
        }
        if (binding != null && binding.getFilters() != null) {
            binding.getFilters().forEach(f -> whereParts.add(buildFilter(f)));
        }
        if (!whereParts.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", whereParts));
        }

        // GROUP BY（有聚合且有维度时）
        if (hasAgg && binding.getDimensions() != null && !binding.getDimensions().isEmpty()) {
            sb.append(" GROUP BY ")
              .append(binding.getDimensions().stream()
                  .map(this::quoteIdent)
                  .collect(Collectors.joining(", ")));
        }

        // LIMIT
        int limit = binding != null && binding.getLimit() != null ? binding.getLimit() : 10_000;
        sb.append(" LIMIT ").append(Math.min(Math.max(limit, 1), 100_000));

        return sb.toString();
    }

    private String baseSelect(Dataset ds) {
        if (ds.getSelectSql() != null && !ds.getSelectSql().isBlank()) {
            return "SELECT * FROM (" + ds.getSelectSql() + ") base";
        }
        if (ds.getAssetFqn() != null) {
            return "SELECT * FROM " + ds.getAssetFqn();
        }
        throw new IllegalArgumentException(
            "dataset " + ds.getId() + " has neither select_sql nor asset_fqn");
    }

    private String buildFilter(DataBinding.Filter f) {
        String field = quoteIdent(f.getField());
        Object v = f.getValue();
        return switch (f.getOp() == null ? "=" : f.getOp()) {
            case "=", "!=", ">", "<", ">=", "<=" -> field + " " + f.getOp() + " " + literal(v);
            case "in" -> field + " IN (" + literal(v) + ")";
            case "like" -> field + " LIKE " + literal(v);
            case "is null" -> field + " IS NULL";
            case "is not null" -> field + " IS NOT NULL";
            default -> throw new IllegalArgumentException("unsupported filter op: " + f.getOp());
        };
    }

    private String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof List<?> list) {
            return list.stream().map(this::literal).collect(Collectors.joining(", "));
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private String quoteIdent(String ident) {
        if (ident == null || ident.isEmpty()) return ident;
        if (ident.startsWith("\"") && ident.endsWith("\"")) return ident;
        return "\"" + ident + "\"";
    }
}
