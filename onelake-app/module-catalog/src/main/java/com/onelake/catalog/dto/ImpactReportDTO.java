package com.onelake.catalog.dto;

import java.util.List;

/**
 * 影响分析返回（对应《血缘图模块完善设计方案》§5.1.3 / §5.2.1）。
 *
 * <p>severity 多维度加权：apis>0 或 subs≥10 → HIGH；jobs≥5 或触及 DWS/ADS → MEDIUM；否则 LOW。
 * severityReasons 给前端展示具体触发改判的依据。
 */
public record ImpactReportDTO(
    String rootFqn,
    List<String> directDownstream,
    List<String> indirectDownstream,
    int affectedJobs,
    int affectedApis,
    int affectedSubscribers,
    String severity,
    List<String> severityReasons
) {}
