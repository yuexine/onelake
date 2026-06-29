package com.onelake.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据集查询入参（前端组件绑定）。
 * 对应 §7.1 DataBinding。
 */
@Data
@Builder
public class DataBinding {

    private String datasetId;
    private List<String> dimensions;
    private List<Measure> measures;
    private List<Filter> filters;
    private Integer refreshSec;
    private Integer limit;

    @Data
    @Builder
    public static class Measure {
        private String field;
        private String agg;   // sum / avg / max / min / count
    }

    @Data
    @Builder
    public static class Filter {
        private String field;
        private String op;    // =, !=, in, >, <, between, etc.
        private Object value;
        private String fromVar;  // 引用全局变量（globalVars）
    }
}
