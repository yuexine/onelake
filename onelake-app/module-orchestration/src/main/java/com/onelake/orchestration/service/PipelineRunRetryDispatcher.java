package com.onelake.orchestration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 定时恢复并派发持久化的 DAG 级失败自动重跑。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineRunRetryDispatcher {

    private final PipelineRunRetryService retryService;

    @Scheduled(
            fixedDelayString = "${onelake.orchestration.run-retry-delay-ms:30000}",
            initialDelayString = "${onelake.orchestration.run-retry-initial-delay-ms:15000}")
    public void tickRetries() {
        // 普通 CRON/MANUAL 运行没有独立终态回调；先主动同步 Dagster，失败运行才能进入候选集合。
        for (UUID runId : retryService.retryWatchRunIds()) {
            try {
                retryService.refreshRunStatus(runId);
            } catch (RuntimeException ex) {
                log.warn("失败自动重跑监控运行 {} 状态同步失败：{}", runId, ex.getMessage());
            }
        }
        Instant now = Instant.now();
        for (UUID runId : retryService.retryCandidateIds(now)) {
            try {
                retryService.retryIfDue(runId, now);
            } catch (RuntimeException ex) {
                log.warn("失败自动重跑候选 {} 处理失败：{}", runId, ex.getMessage());
            }
        }
    }
}
