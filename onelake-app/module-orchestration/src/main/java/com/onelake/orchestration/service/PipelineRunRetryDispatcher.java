package com.onelake.orchestration.service;

import com.onelake.orchestration.repository.SchedulerLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 定时收口活跃 Dagster 运行，并派发持久化的 DAG 级失败自动重跑。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineRunRetryDispatcher {

    private static final String STATUS_SYNC_LOCK_KEY = "pipeline-run-status-sync";
    private static final Duration STATUS_SYNC_LOCK_TTL = Duration.ofMinutes(5);

    private final PipelineRunRetryService retryService;
    private final SchedulerLockRepository schedulerLockRepo;

    @Scheduled(
            fixedDelayString = "${onelake.orchestration.run-retry-delay-ms:30000}",
            initialDelayString = "${onelake.orchestration.run-retry-initial-delay-ms:15000}")
    public void tickRetries() {
        Instant now = Instant.now();
        String holder = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = schedulerLockRepo.acquire(
                    STATUS_SYNC_LOCK_KEY, holder, now.plus(STATUS_SYNC_LOCK_TTL)) == 1;
        } catch (RuntimeException ex) {
            log.warn("活跃运行状态同步锁获取失败，本轮跳过：{}", ex.getMessage());
            return;
        }
        if (!acquired) {
            log.debug("活跃运行状态同步正由其他副本执行，本轮跳过");
            return;
        }
        try {
            syncActiveRuns();
            dispatchDueRetries(now);
        } finally {
            try {
                schedulerLockRepo.release(STATUS_SYNC_LOCK_KEY, holder);
            } catch (RuntimeException ex) {
                log.warn("活跃运行状态同步锁释放失败：{}", ex.getMessage());
            }
        }
    }

    private void syncActiveRuns() {
        // EVENT/CRON/MANUAL 运行都可能没有独立终态回调；统一主动同步，避免长期占用 maxActiveRuns。
        for (UUID runId : retryService.activeRunIds()) {
            try {
                retryService.refreshRunStatus(runId);
            } catch (RuntimeException ex) {
                log.warn("活跃 Dagster 运行 {} 状态同步失败：{}", runId, ex.getMessage());
            }
        }
    }

    private void dispatchDueRetries(Instant now) {
        for (UUID runId : retryService.retryCandidateIds(now)) {
            try {
                retryService.retryIfDue(runId, now);
            } catch (RuntimeException ex) {
                log.warn("失败自动重跑候选 {} 处理失败：{}", runId, ex.getMessage());
            }
        }
    }
}
