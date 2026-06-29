package com.onelake.analytics.domain.enums;

/**
 * 算法模板分类。
 */
public enum TemplateCategory {
    CLUSTERING,   // 聚类（KMeans、DBSCAN）
    REGRESSION,   // 回归（线性、树模型）
    FORECAST,     // 时序预测（Prophet、ARIMA）
    CORRELATION,  // 相关性分析
    EDA,          // 探索性分析
    RFM           // RFM 客户分群
}
