package com.onelake.analytics.service;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.dto.DataBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SqlBuilder 单元测试 —— 覆盖 v1.1 评审关键边界：
 * 1. 脱敏完全下推到 Trino（mask 表达式注入 SELECT）
 * 2. row_filter 注入 WHERE
 * 3. 聚合/筛选/分页按 DataBinding 组装
 * 4. ASSET / SQL / 缺失配置分支
 */
class SqlBuilderTest {

    private final SqlBuilder builder = new SqlBuilder();

    private Dataset asset(String fqn) {
        Dataset d = new Dataset();
        d.setId(UUID.randomUUID());
        d.setSourceType(SourceType.ASSET);
        d.setAssetFqn(fqn);
        d.setCacheTtlSec(60);
        return d;
    }

    @Test
    void noAgg_selectStar_fromAssetFqn() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        String sql = builder.compose(ds, null, Map.of());
        assertThat(sql).contains("SELECT *");
        assertThat(sql).contains("FROM (SELECT * FROM iceberg.dwd.dwd_user) t");
        assertThat(sql).endsWith(" LIMIT 10000");
    }

    @Test
    void noAgg_rowFilter_injectedIntoWhere() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        ds.setRowFilter("region = '华东'");
        String sql = builder.compose(ds, null, Map.of());
        assertThat(sql).contains("WHERE (region = '华东')");
    }

    @Test
    void agg_measures_useConfiguredAggAndGroupBy() {
        Dataset ds = asset("iceberg.dws.ads_order_gmv_daily");
        DataBinding binding = DataBinding.builder()
            .dimensions(List.of("stat_date"))
            .measures(List.of(
                DataBinding.Measure.builder().field("gmv").agg("sum").build(),
                DataBinding.Measure.builder().field("orders").agg("count").build()))
            .build();
        String sql = builder.compose(ds, binding, Map.of());
        assertThat(sql).contains("sum(\"gmv\") AS \"gmv\"");
        assertThat(sql).contains("count(\"orders\") AS \"orders\"");
        assertThat(sql).contains("GROUP BY \"stat_date\"");
    }

    @Test
    void masking_pushDownToTrino_replacesColumnInSelect() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder()
            .dimensions(List.of("user_id"))
            .measures(List.of(
                DataBinding.Measure.builder().field("mobile").agg("count").build()))
            .build();
        // mobile 列下推脱敏：mask_phone(mobile)
        Map<String, String> masking = Map.of("mobile", "mask_phone(mobile)");
        String sql = builder.compose(ds, binding, masking);
        // 关键断言：聚合内层是 mask 表达式而非裸列
        assertThat(sql).contains("count(mask_phone(mobile)) AS \"mobile\"");
        assertThat(sql).doesNotContain("count(\"mobile\")");
    }

    @Test
    void filters_injectedAsAndConditions() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder()
            .dimensions(List.of("user_id"))
            .filters(List.of(
                DataBinding.Filter.builder().field("age").op(">").value(18).build(),
                DataBinding.Filter.builder().field("status").op("=").value("active").build()))
            .build();
        String sql = builder.compose(ds, binding, Map.of());
        assertThat(sql).contains("\"age\" > 18");
        assertThat(sql).contains("\"status\" = 'active'");
        assertThat(sql).contains("WHERE");
        assertThat(sql).contains("AND");
    }

    @Test
    void filter_inOperator_handlesListValue() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder()
            .filters(List.of(
                DataBinding.Filter.builder().field("region").op("in")
                    .value(List.of("华东", "华南")).build()))
            .build();
        String sql = builder.compose(ds, binding, Map.of());
        assertThat(sql).contains("\"region\" IN ('华东', '华南')");
    }

    @Test
    void limit_boundedToOneHundredThousand() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder().limit(500_000).build();
        String sql = builder.compose(ds, binding, Map.of());
        assertThat(sql).endsWith(" LIMIT 100000");
    }

    @Test
    void limit_minimumIsOne() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder().limit(0).build();
        String sql = builder.compose(ds, binding, Map.of());
        assertThat(sql).endsWith(" LIMIT 1");
    }

    @Test
    void sqlSourceType_usesSelectSqlAsBase() {
        Dataset ds = new Dataset();
        ds.setId(UUID.randomUUID());
        ds.setSourceType(SourceType.SQL);
        ds.setSelectSql("SELECT user_id, mobile FROM iceberg.dwd.dwd_user WHERE age > 18");
        ds.setCacheTtlSec(60);
        String sql = builder.compose(ds, null, Map.of());
        // 关键：把用户 SQL 包成子查询，避免 WHERE 注入冲突
        assertThat(sql).contains("FROM (SELECT user_id, mobile FROM iceberg.dwd.dwd_user WHERE age > 18) base");
    }

    @Test
    void missingBothSelectSqlAndFqn_throws() {
        Dataset ds = new Dataset();
        ds.setId(UUID.randomUUID());
        ds.setSourceType(SourceType.ASSET);
        ds.setCacheTtlSec(60);
        // 既无 select_sql 也无 asset_fqn
        assertThatThrownBy(() -> builder.compose(ds, null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("neither select_sql nor asset_fqn");
    }

    @Test
    void unsupportedFilterOp_throws() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        DataBinding binding = DataBinding.builder()
            .filters(List.of(
                DataBinding.Filter.builder().field("age").op("regex").value("\\d+").build()))
            .build();
        assertThatThrownBy(() -> builder.compose(ds, binding, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported filter op");
    }

    @Test
    void rowFilter_ANDsWithBindingFilters() {
        Dataset ds = asset("iceberg.dwd.dwd_user");
        ds.setRowFilter("tenant_id = 't1'");
        DataBinding binding = DataBinding.builder()
            .filters(List.of(
                DataBinding.Filter.builder().field("status").op("=").value("active").build()))
            .build();
        String sql = builder.compose(ds, binding, Map.of());
        // 两个条件都要 AND 起来，且 row_filter 用括号包裹避免优先级错乱
        assertThat(sql).contains("WHERE (tenant_id = 't1') AND \"status\" = 'active'");
    }
}
