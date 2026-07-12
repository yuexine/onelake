package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.domain.enums.ScheduleMode;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DAG 级失败自动重跑状态机。
 *
 * <p>每次重跑都创建新的 AUTO_RETRY JobRun，并通过 retrySourceRunId 形成运行链；
 * task_run.attempt/maxRetries 仍只描述单个 JobRun 内的节点级 M1 重试。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineRunRetryService {

    private static final List<DagStatus> ACTIVE_RUN_STATUSES =
            List.of(DagStatus.QUEUED, DagStatus.RUNNING);
    private static final int ACTIVE_SCAN_BATCH_SIZE = 100;
    private static final int RETRY_SCAN_BATCH_SIZE = 100;

    private final JobRunRepository runRepo;
    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;

    /** 读取一页持久化候选；逐条派发时会再次加锁并重判。 */
    @Transactional(readOnly = true)
    public List<UUID> retryCandidateIds(Instant now) {
        return runRepo.findRetryCandidateIds(
                now,
                PageRequest.of(0, RETRY_SCAN_BATCH_SIZE));
    }

    /** 返回全部需要后台主动同步 Dagster 终态的运行。 */
    @Transactional(readOnly = true)
    public List<UUID> activeRunIds() {
        return runRepo.findActiveDagsterRunIds(PageRequest.of(0, ACTIVE_SCAN_BATCH_SIZE));
    }

    /**
     * 在恢复运行所属租户上下文后同步远端状态，保证后台发布的终态事件仍带正确租户。
     */
    public void refreshRunStatus(UUID runId) {
        JobRun run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        Dag dag = dagRepo.findById(run.getDagId()).orElse(null);
        if (dag == null) {
            return;
        }
        TenantSnapshot previousTenant = TenantSnapshot.capture();
        TenantContext.setTenantId(dag.getTenantId());
        if (run.getTriggeredBy() != null) TenantContext.setUserId(run.getTriggeredBy());
        if (StringUtils.hasText(run.getTriggeredByName())) TenantContext.setUsername(run.getTriggeredByName());
        try {
            orchestrationService.refreshRunStatusForAutomation(runId);
        } finally {
            previousTenant.restore();
        }
    }

    /**
     * 尝试领取并重跑一条失败运行。
     *
     * @return 本轮是否实际调用了自动重跑触发入口
     */
    @Transactional
    public boolean retryIfDue(UUID sourceRunId, Instant now) {
        JobRun source = runRepo.findByIdForUpdate(sourceRunId).orElse(null);
        if (source == null
                || source.getStatus() != DagStatus.FAILED
                || source.getRetryDispatchedAt() != null) {
            return false;
        }
        // 不同失败来源可能属于同一 DAG；DAG 行锁必须覆盖活跃数检查和新运行创建，
        // 才能在多实例派发时严格遵守 maxActiveRuns。
        Dag dag = dagRepo.findByIdForUpdate(source.getDagId()).orElse(null);
        if (dag == null) {
            source.setRetryDispatchedAt(now);
            runRepo.save(source);
            return false;
        }

        int retryLimit = Math.max(0, dag.getRunRetryCount() == null ? 0 : dag.getRunRetryCount());
        int currentAttempt = Math.max(0,
                source.getRunRetryAttempt() == null ? 0 : source.getRunRetryAttempt());
        if (currentAttempt >= retryLimit) {
            // 固化“已检查且达到上限”，避免每个 tick 重复扫描同一失败终态。
            source.setRetryDispatchedAt(now);
            runRepo.save(source);
            return false;
        }
        if (ScheduleMode.from(dag.getScheduleMode()) == ScheduleMode.FROZEN) {
            // 冻结期间保留候选，解除冻结后可继续按原失败链重跑。
            return false;
        }

        int intervalSeconds = Math.max(0,
                dag.getRunRetryIntervalSeconds() == null ? 0 : dag.getRunRetryIntervalSeconds());
        Instant terminalAt = source.getFinishedAt() == null
                ? source.getUpdatedAt()
                : source.getFinishedAt();
        if (terminalAt == null || terminalAt.plusSeconds(intervalSeconds).isAfter(now)) {
            return false;
        }

        int maxActiveRuns = Math.max(1,
                dag.getMaxActiveRuns() == null ? 1 : dag.getMaxActiveRuns());
        if (runRepo.countByDagIdAndStatusInAndRunModeNot(
                dag.getId(), ACTIVE_RUN_STATUSES, RunEnvironment.DEV.name()) >= maxActiveRuns) {
            return false;
        }

        // 先持久化领取标记；即使后续编译或 Dagster 启动失败，也不会重复消费同一次额度。
        source.setRetryDispatchedAt(now);
        runRepo.save(source);

        TenantSnapshot previousTenant = TenantSnapshot.capture();
        TenantContext.setTenantId(dag.getTenantId());
        if (source.getTriggeredBy() != null) {
            TenantContext.setUserId(source.getTriggeredBy());
        }
        if (StringUtils.hasText(source.getTriggeredByName())) {
            TenantContext.setUsername(source.getTriggeredByName());
        }
        try {
            UUID retryRunId = orchestrationService.triggerPipelineRetry(source);
            log.info("流水线 {} 失败运行 {} 已自动重跑，attempt={} retryRunId={}",
                    dag.getId(), source.getId(), currentAttempt + 1, retryRunId);
            return true;
        } catch (RuntimeException ex) {
            // triggerPipelineRetry 自身保留已创建的失败 JobRun；这里隔离异常让其他候选继续派发。
            log.warn("流水线 {} 失败运行 {} 自动重跑触发失败：{}",
                    dag.getId(), source.getId(), ex.getMessage());
            return true;
        } finally {
            previousTenant.restore();
        }
    }

    private record TenantSnapshot(UUID tenantId, UUID userId, String username, String traceId) {
        static TenantSnapshot capture() {
            return new TenantSnapshot(
                    TenantContext.getTenantId(),
                    TenantContext.getUserId(),
                    TenantContext.getUsername(),
                    TenantContext.getTraceId());
        }

        void restore() {
            TenantContext.clear();
            if (tenantId != null) TenantContext.setTenantId(tenantId);
            if (userId != null) TenantContext.setUserId(userId);
            if (StringUtils.hasText(username)) TenantContext.setUsername(username);
            if (StringUtils.hasText(traceId) && !"-".equals(traceId)) TenantContext.setTraceId(traceId);
        }
    }
}
