package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 定时扫描进行中流水线运行，并处理 SLA 违约与运行超时。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitorService {

    private static final String SLA_MONITOR_LOCK_KEY = "pipeline-sla-monitor";
    private static final Duration SLA_MONITOR_LOCK_TTL = Duration.ofMinutes(5);
    private static final List<DagStatus> ACTIVE_RUN_STATUSES =
            List.of(DagStatus.QUEUED, DagStatus.RUNNING);

    private final JobRunRepository jobRunRepo;
    private final SchedulerLockRepository schedulerLockRepo;
    private final SlaMonitorRunProcessor runProcessor;

    /** 默认每 60 秒巡检一次；参数可由部署配置覆盖。 */
    @Scheduled(
            fixedDelayString = "${onelake.orchestration.sla-monitor-delay-ms:60000}",
            initialDelayString = "${onelake.orchestration.sla-monitor-initial-delay-ms:30000}")
    public void tickSlaMonitor() {
        tickSlaMonitor(Instant.now());
    }

    /** 使用固定时间执行一次巡检，便于测试严格的大于阈值语义。 */
    void tickSlaMonitor(Instant now) {
        String holder = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = schedulerLockRepo.acquire(
                    SLA_MONITOR_LOCK_KEY, holder, now.plus(SLA_MONITOR_LOCK_TTL)) == 1;
        } catch (RuntimeException ex) {
            log.warn("SlaMonitorService：获取调度锁失败，本轮跳过：{}", ex.getMessage());
            return;
        }
        if (!acquired) {
            log.debug("SlaMonitorService：调度锁正由其他实例持有，本轮跳过");
            return;
        }

        try {
            for (UUID runId : jobRunRepo.findSlaMonitorCandidateIds(ACTIVE_RUN_STATUSES)) {
                try {
                    // 每条 run 独立事务；单条异常不阻断同一轮其他候选。
                    runProcessor.process(runId, now);
                } catch (RuntimeException ex) {
                    log.warn("SlaMonitorService：处理运行 {} 失败：{}", runId, ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("SlaMonitorService：加载巡检候选失败：{}", ex.getMessage());
        } finally {
            try {
                int released = schedulerLockRepo.release(SLA_MONITOR_LOCK_KEY, holder);
                if (released == 0) {
                    log.warn("SlaMonitorService：调度锁已过期或被接管，未释放当前 holder 的锁");
                }
            } catch (RuntimeException ex) {
                log.warn("SlaMonitorService：释放调度锁失败：{}", ex.getMessage());
            }
        }
    }
}
