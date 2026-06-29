package com.onelake.analytics.domain.enums;

/**
 * 数据集来源类型（对应《数据分析与可视化模块设计方案》§6 dataset.source_type）。
 */
public enum SourceType {
    ASSET,    // Iceberg 表资产（asset_fqn）
    SQL,      // 自定义 Trino SQL（select_sql）
    API,      // 数据服务 PostgREST API（api_id）
    NOTEBOOK  // Notebook 产出（asset_fqn 由 publish() 创建）
}
