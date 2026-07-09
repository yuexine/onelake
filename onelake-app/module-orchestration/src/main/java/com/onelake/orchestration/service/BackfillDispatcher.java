package com.onelake.orchestration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 回填派发 tick：按 backfill.max_parallel 把业务日期明细转成子 JobRun。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillDispatcher {

    private final BackfillService backfillService;

    @Scheduled(
            fixedDelayString = "${onelake.orchestration.backfill-dispatch-delay-ms:30000}",
            initialDelayString = "${onelake.orchestration.backfill-dispatch-initial-delay-ms:10000}")
    public void tickBackfills() {
        for (UUID backfillId : backfillService.activeBackfillIds()) {
            try {
                int dispatched = backfillService.dispatchBackfill(backfillId);
                if (dispatched > 0) {
                    log.info("BackfillDispatcher：回填批次 {} 本轮派发 {} 个子 run", backfillId, dispatched);
                }
            } catch (RuntimeException ex) {
                log.warn("BackfillDispatcher：回填批次 {} 派发失败：{}", backfillId, ex.getMessage());
            }
        }
    }
}
