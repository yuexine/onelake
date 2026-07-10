package com.onelake.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 回填批次响应对象。
 *
 * <p>同时返回聚合进度、批次策略和可选明细；列表接口可以省略 runs，详情接口返回完整明细。
 *
 * @param id 回填批次 ID
 * @param dagId 被补跑的 DAG
 * @param status 批次聚合状态
 * @param total 计划周期总数
 * @param succeeded 成功周期数
 * @param failed 失败或取消周期数
 * @param maxParallel 批次并发上限
 * @param range logical_date 展示范围
 * @param grain 业务区间粒度
 * @param timezone 创建批次时冻结的业务时区
 * @param createdAt 创建时间
 * @param updatedAt 最近进度更新时间
 * @param runs 子运行明细
 */
public record BackfillDTO(
        UUID id,
        @JsonProperty("dag_id")
        UUID dagId,
        String status,
        int total,
        int succeeded,
        int failed,
        @JsonProperty("max_parallel")
        int maxParallel,
        Range range,
        String grain,
        String timezone,
        @JsonProperty("created_at")
        Instant createdAt,
        @JsonProperty("updated_at")
        Instant updatedAt,
        List<BackfillRunDTO> runs
) {
    public BackfillDTO {
        range = range == null ? new Range(null, null) : range;
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    public Instant rangeStart() {
        return range.start();
    }

    public Instant rangeEnd() {
        return range.end();
    }

    public int totalRuns() {
        return total;
    }

    public int succeededRuns() {
        return succeeded;
    }

    public int failedRuns() {
        return failed;
    }

    /** @param start 首个 logical_date @param end 最后一个 logical_date */
    public record Range(
            Instant start,
            Instant end
    ) {}
}
