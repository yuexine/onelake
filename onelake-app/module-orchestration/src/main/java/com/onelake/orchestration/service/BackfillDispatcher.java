package com.onelake.orchestration.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 回填派发 tick：按 backfill.max_parallel 把业务日期明细转成子 JobRun。
 */
@Service
@Slf4j
public class BackfillDispatcher {

    private final BackfillService backfillService;
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

    @Scheduled(
            fixedDelayString = "${onelake.orchestration.backfill-dispatch-delay-ms:30000}",
            initialDelayString = "${onelake.orchestration.backfill-dispatch-initial-delay-ms:10000}")
    public void tickBackfills() {
        for (UUID backfillId : backfillService.activeBackfillIds()) {
            dispatchOne(backfillId, "定时派发");
        }
    }

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
