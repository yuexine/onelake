package com.onelake.orchestration.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 回填派发 tick：按 backfill.max_parallel 把业务日期明细转成子 JobRun。
 */
@Service
@Slf4j
public class BackfillDispatcher {

    /** 复用 M1 的批次创建、加锁派发、状态聚合和取消语义。 */
    private final BackfillService backfillService;
    /** 隔离回填首轮派发，避免 scheduler tick 被 Dagster 启动过程阻塞。 */
    private final TaskExecutor backfillDispatchExecutor;

    public BackfillDispatcher(BackfillService backfillService,
                              @Qualifier("backfillDispatchExecutor") TaskExecutor backfillDispatchExecutor) {
        this.backfillService = backfillService;
        this.backfillDispatchExecutor = backfillDispatchExecutor;
    }

    /**
     * 在批次提交后异步发起首轮派发；线程池繁忙时由定时 tick 继续恢复。
     */
    public void dispatchNow(UUID backfillId) {
        try {
            backfillDispatchExecutor.execute(() -> dispatchOne(backfillId, "创建后异步派发"));
        } catch (TaskRejectedException ex) {
            log.warn("BackfillDispatcher：回填批次 {} 的立即派发提交被拒绝，等待定时调度恢复", backfillId);
        }
    }

    /**
     * 把调度器精确展开的历史 cron 周期持久化到 M1 队列并发起首轮派发。
     *
     * <p>先同步提交数据库事务，再异步派发。线程池拒绝任务时，已持久化批次仍会由
     * {@link #tickBackfills()} 恢复，不会丢失 catchup 计划。
     *
     * @param dagId 需要补跑的流水线 ID
     * @param intervals 已按 cron 展开的精确业务区间
     * @param grain DAG 分区粒度
     * @param maxParallel 批次并发上限
     * @return 已创建的 backfill 批次 ID
     */
    public UUID dispatchCatchup(UUID dagId,
                                List<DataIntervalCalculator.DataInterval> intervals,
                                String grain,
                                int maxParallel) {
        UUID backfillId = backfillService.createCatchupBackfill(
                dagId, intervals, grain, maxParallel).id();
        dispatchNow(backfillId);
        return backfillId;
    }

    /** 定期扫描持久化的 QUEUED/RUNNING 批次，负责进程重启和线程池拥塞后的恢复派发。 */
    @Scheduled(
            fixedDelayString = "${onelake.orchestration.backfill-dispatch-delay-ms:30000}",
            initialDelayString = "${onelake.orchestration.backfill-dispatch-initial-delay-ms:10000}")
    public void tickBackfills() {
        for (UUID backfillId : backfillService.activeBackfillIds()) {
            dispatchOne(backfillId, "定时派发");
        }
    }

    /** 隔离单批次异常，确保一个损坏或暂时失败的批次不会中断其余恢复派发。 */
    private void dispatchOne(UUID backfillId, String source) {
        try {
            int dispatched = backfillService.dispatchBackfill(backfillId);
            if (dispatched > 0) {
                log.info("BackfillDispatcher：回填批次 {} {} {} 个子 run", backfillId, source, dispatched);
            }
        } catch (RuntimeException ex) {
            log.warn("BackfillDispatcher：回填批次 {} {}失败：{}", backfillId, source, ex.getMessage());
        }
    }
}
