package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Backfill;
import com.onelake.orchestration.domain.entity.BackfillRun;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.BackfillRunStatus;
import com.onelake.orchestration.domain.enums.BackfillStatus;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.BackfillRunDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.BackfillRepository;
import com.onelake.orchestration.repository.BackfillRunRepository;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduler-managed 业务日期区间回填服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillService {

    /** 单个回填批次允许持久化的最大业务周期数，同时作为 catchup 放大保护。 */
    private static final int MAX_BACKFILL_RUNS = 10_000;

    private final BackfillRepository backfillRepo;
    private final BackfillRunRepository backfillRunRepo;
    private final DagRepository dagRepo;
    private final JobRunRepository jobRunRepo;
    private final OrchestrationService orchestrationService;

    /**
     * 创建用户指定连续业务日期范围的回填批次。
     *
     * <p>rangeStart/rangeEnd 都表示 logical_date 且包含端点；窗口按 DAG 时区和粒度展开。
     *
     * @param dagId 被补跑的流水线
     * @param rangeStart 首个 logical_date
     * @param rangeEnd 最后一个 logical_date
     * @param grain 可选粒度，缺省使用 DAG partitionGrain
     * @param maxParallel 批次内部最大并行数
     * @return 已持久化的批次和明细摘要
     */
    @Transactional
    public BackfillDTO createBackfill(UUID dagId,
                                      Instant rangeStart,
                                      Instant rangeEnd,
                                      String grain,
                                      Integer maxParallel) {
        UUID tenantId = requireTenant();
        Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
        BackfillGrain normalizedGrain = BackfillGrain.parse(
                StringUtils.hasText(grain) ? grain : dag.getPartitionGrain());
        String timezone = StringUtils.hasText(dag.getTimezone()) ? dag.getTimezone() : "Asia/Shanghai";
        List<BackfillWindow> windows = expandWindows(rangeStart, rangeEnd, normalizedGrain, timezone);

        return persistBackfill(
                tenantId,
                dag,
                rangeStart,
                rangeEnd,
                normalizedGrain,
                timezone,
                maxParallel,
                windows);
    }

    /**
     * 为 scheduler catchup 持久化精确 cron 周期，避免用连续日期范围补出未命中的周期。
     *
     * <p>该入口不对外暴露 REST 契约，由 {@link BackfillDispatcher} 调用。与普通范围回填
     * 共用同一组 backfill/backfill_run 表和后续派发状态机。
     *
     * @param dagId 需要补跑的流水线 ID
     * @param intervals CatchupPlanner 已按 cron 展开的精确业务区间
     * @param grain DAG 的分区粒度，用于冻结回填批次元数据
     * @param maxParallel 回填批次并发上限；实际派发还会受 DAG max_active_runs 限制
     * @return 已持久化的回填批次及明细摘要
     */
    @Transactional
    public BackfillDTO createCatchupBackfill(
            UUID dagId,
            List<DataIntervalCalculator.DataInterval> intervals,
            String grain,
            Integer maxParallel) {
        UUID tenantId = requireTenant();
        Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
        if (intervals == null || intervals.isEmpty()) {
            throw new BizException(40020, "catchup intervals 不能为空");
        }
        if (intervals.size() > MAX_BACKFILL_RUNS) {
            throw new BizException(40022, "回填区间过大，最多支持 " + MAX_BACKFILL_RUNS + " 个业务日期");
        }

        BackfillGrain normalizedGrain = BackfillGrain.parse(
                StringUtils.hasText(grain) ? grain : dag.getPartitionGrain());
        String timezone = StringUtils.hasText(dag.getTimezone()) ? dag.getTimezone() : "Asia/Shanghai";
        // 精确周期可能是周调度等稀疏序列，不能复用 rangeStart/rangeEnd 连续展开。
        List<BackfillWindow> windows = intervals.stream()
                .sorted(java.util.Comparator.comparing(DataIntervalCalculator.DataInterval::logicalDate))
                .map(this::toBackfillWindow)
                .toList();
        return persistBackfill(
                tenantId,
                dag,
                windows.get(0).logicalDate(),
                windows.get(windows.size() - 1).logicalDate(),
                normalizedGrain,
                timezone,
                maxParallel,
                windows);
    }

    /**
     * 普通范围回填与 scheduler catchup 共用的批次/明细持久化逻辑。
     *
     * <p>调用方负责提前生成并校验窗口；本方法只冻结批次元数据并按窗口顺序创建
     * QUEUED 明细，后续实际 JobRun 由 BackfillDispatcher 异步产生。
     */
    private BackfillDTO persistBackfill(
            UUID tenantId,
            Dag dag,
            Instant rangeStart,
            Instant rangeEnd,
            BackfillGrain normalizedGrain,
            String timezone,
            Integer maxParallel,
            List<BackfillWindow> windows) {

        Backfill backfill = new Backfill();
        backfill.setTenantId(tenantId);
        backfill.setDagId(dag.getId());
        backfill.setRangeStart(rangeStart);
        backfill.setRangeEnd(rangeEnd);
        backfill.setGrain(normalizedGrain.name());
        backfill.setTimezone(timezone);
        backfill.setStatus(BackfillStatus.QUEUED);
        backfill.setTotalRuns(windows.size());
        backfill.setSucceededRuns(0);
        backfill.setFailedRuns(0);
        backfill.setMaxParallel(Math.max(1, maxParallel == null ? 1 : maxParallel));
        backfill.setCreatedBy(TenantContext.getUserId());
        backfill.setCreatedByName(currentActorName());
        backfill.setCreatedAt(Instant.now());
        backfill.setUpdatedAt(backfill.getCreatedAt());
        backfillRepo.save(backfill);

        List<BackfillRun> plannedRuns = new ArrayList<>();
        for (BackfillWindow window : windows) {
            BackfillRun run = new BackfillRun();
            run.setTenantId(tenantId);
            run.setBackfillId(backfill.getId());
            run.setDagId(dag.getId());
            run.setLogicalDate(window.logicalDate());
            run.setDataIntervalStart(window.dataIntervalStart());
            run.setDataIntervalEnd(window.dataIntervalEnd());
            run.setStatus(BackfillRunStatus.QUEUED);
            plannedRuns.add(run);
        }
        backfillRunRepo.saveAll(plannedRuns);
        log.info("已创建回填批次 dagId={} backfillId={} grain={} totalRuns={} maxParallel={}",
                dag.getId(), backfill.getId(), normalizedGrain, plannedRuns.size(), backfill.getMaxParallel());
        return toDTO(backfill, plannedRuns);
    }

    @Transactional(readOnly = true)
    public BackfillDTO getBackfill(UUID id) {
        UUID tenantId = requireTenant();
        Backfill backfill = backfillRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        return toDTO(backfill, backfillRunRepo.findByBackfillIdOrderByLogicalDateAsc(id));
    }

    @Transactional(readOnly = true)
    public List<BackfillDTO> listBackfills(UUID dagId) {
        UUID tenantId = requireTenant();
        Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
        return backfillRepo.findByDagIdOrderByCreatedAtDesc(dag.getId()).stream()
                .filter(backfill -> tenantId.equals(backfill.getTenantId()))
                .map(backfill -> toDTO(backfill, List.of()))
                .toList();
    }

    @Transactional
    public Page<JobRunDTO> listJobRuns(UUID id, Pageable pageable) {
        UUID tenantId = requireTenant();
        Backfill backfill = backfillRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        return orchestrationService.listBackfillRuns(backfill.getDagId(), id, pageable);
    }

    @Transactional
    public JobRunDTO getJobRun(UUID id, UUID runId) {
        UUID tenantId = requireTenant();
        Backfill backfill = backfillRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        return orchestrationService.getBackfillRun(backfill.getDagId(), id, runId);
    }

    @Transactional(readOnly = true)
    public List<UUID> activeBackfillIds() {
        return backfillRepo.findByStatusInOrderByCreatedAtAsc(
                        List.of(BackfillStatus.QUEUED, BackfillStatus.RUNNING))
                .stream()
                .map(Backfill::getId)
                .toList();
    }

    /**
     * 为一个回填批次执行单轮状态同步和队列派发。
     *
     * <p>方法在事务中悲观锁定批次及被领取明细，先同步已运行子任务，再计算批次级与
     * DAG 级剩余槽位，最后按 logical_date 顺序领取 QUEUED 明细。返回后由定时 tick
     * 继续推进尚未完成的批次。
     *
     * @param backfillId 回填批次 ID
     * @return 本轮实际尝试派发的子运行数量
     */
    @Transactional(noRollbackFor = BizException.class)
    public int dispatchBackfill(UUID backfillId) {
        Backfill backfill = backfillRepo.findByIdForUpdate(backfillId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        if (backfill.getStatus() == BackfillStatus.CANCELLED || isTerminal(backfill.getStatus())) {
            return 0;
        }

        // 异步 Dispatcher 没有请求线程上下文；从批次快照恢复租户和创建人，并在 finally 还原。
        TenantSnapshot previousTenant = TenantSnapshot.capture();
        TenantContext.setTenantId(backfill.getTenantId());
        if (TenantContext.getUserId() == null && backfill.getCreatedBy() != null) {
            TenantContext.setUserId(backfill.getCreatedBy());
        }
        if (!StringUtils.hasText(TenantContext.getUsername())
                && StringUtils.hasText(backfill.getCreatedByName())) {
            TenantContext.setUsername(backfill.getCreatedByName());
        }
        try {
            // 先把外部 JobRun 终态同步回队列，释放已结束子运行占用的批次槽位。
            syncChildRuns(backfill);
            if (isTerminal(backfill.getStatus()) || backfill.getStatus() == BackfillStatus.CANCELLED) {
                return 0;
            }

            long running = backfillRunRepo.countByBackfillIdAndStatus(backfill.getId(), BackfillRunStatus.RUNNING);
            int maxParallel = Math.max(1, backfill.getMaxParallel() == null ? 1 : backfill.getMaxParallel());
            // 第一层限制当前批次自身的 max_parallel，第二层限制 DAG 全部触发来源的活跃运行数。
            int batchSlots = Math.max(0, maxParallel - Math.toIntExact(Math.min(running, Integer.MAX_VALUE)));
            int dagSlots = availableDagSlots(backfill, maxParallel);
            int slots = Math.min(batchSlots, dagSlots);
            if (slots == 0) {
                aggregateBackfill(backfill);
                return 0;
            }

            List<BackfillRun> queuedRuns = backfillRunRepo.findByBackfillIdAndStatusForUpdate(
                    backfill.getId(), BackfillRunStatus.QUEUED, PageRequest.of(0, slots));
            String timezone = resolveBackfillTimezone(backfill);
            int dispatched = 0;
            // Repository 已按 logical_date 排序并加锁，保证业务周期有序领取且避免多实例重复领取。
            for (BackfillRun backfillRun : queuedRuns) {
                if (backfill.getStatus() == BackfillStatus.CANCELLED) {
                    break;
                }
                dispatchChildRun(backfill, backfillRun, timezone);
                dispatched++;
            }
            aggregateBackfill(backfill);
            return dispatched;
        } finally {
            previousTenant.restore();
        }
    }

    /**
     * 取消批次并停止后续派发。
     *
     * <p>QUEUED 明细直接取消；RUNNING 明细先尽力终止关联 JobRun，再把本地队列状态
     * 置为 CANCELLED。已经成功的明细保持原状态以保留真实进度。
     */
    @Transactional
    public BackfillDTO cancelBackfill(UUID id) {
        UUID tenantId = requireTenant();
        Backfill backfill = backfillRepo.findByIdAndTenantIdForUpdate(id, tenantId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        List<BackfillRun> runs = backfillRunRepo.findByBackfillIdForUpdate(id);
        Instant now = Instant.now();
        backfill.setStatus(BackfillStatus.CANCELLED);
        backfill.setUpdatedAt(now);

        for (BackfillRun run : runs) {
            if (run.getStatus() == BackfillRunStatus.QUEUED) {
                run.setStatus(BackfillRunStatus.CANCELLED);
                run.setUpdatedAt(now);
                backfillRunRepo.save(run);
                continue;
            }
            if (run.getStatus() == BackfillRunStatus.RUNNING) {
                if (run.getJobRunId() != null) {
                    orchestrationService.cancelRun(run.getJobRunId());
                }
                run.setStatus(BackfillRunStatus.CANCELLED);
                run.setUpdatedAt(now);
                backfillRunRepo.save(run);
            }
        }
        aggregateBackfill(backfill);
        backfill.setStatus(BackfillStatus.CANCELLED);
        backfill.setUpdatedAt(now);
        backfillRepo.save(backfill);
        return toDTO(backfill, runs);
    }

    /**
     * 将一个已领取明细转换为真实 BACKFILL JobRun。
     *
     * <p>先把明细置为 RUNNING 并持久化，确保进程中断后能够被恢复扫描识别；启动失败
     * 则记录 FAILED 和截断后的错误信息，不回滚整个批次中其他周期。
     */
    private void dispatchChildRun(Backfill backfill, BackfillRun backfillRun, String timezone) {
        Instant now = Instant.now();
        backfillRun.setStatus(BackfillRunStatus.RUNNING);
        backfillRun.setUpdatedAt(now);
        backfillRunRepo.save(backfillRun);
        try {
            UUID runId = orchestrationService.triggerPipelineRun(
                    backfillRun.getDagId(),
                    TriggerType.BACKFILL,
                    new RunContext(
                            backfillRun.getLogicalDate(),
                            backfillRun.getDataIntervalStart(),
                            backfillRun.getDataIntervalEnd(),
                            timezone,
                            "NORMAL",
                            backfill.getId(),
                            TriggerType.BACKFILL));
            backfillRun.setJobRunId(runId);
            backfillRun.setStatus(BackfillRunStatus.RUNNING);
            backfillRun.setErrorMsg(null);
            backfillRun.setUpdatedAt(Instant.now());
            backfillRunRepo.save(backfillRun);
        } catch (RuntimeException ex) {
            backfillRun.setStatus(BackfillRunStatus.FAILED);
            backfillRun.setErrorMsg(truncate(ex.getMessage(), 3900));
            backfillRun.setUpdatedAt(Instant.now());
            backfillRunRepo.save(backfillRun);
            log.warn("回填子 run 派发失败 backfillId={} logicalDate={}：{}",
                    backfill.getId(), backfillRun.getLogicalDate(), ex.getMessage());
        }
    }

    /** 查询 RUNNING 明细关联的 JobRun，并把终态单调同步回回填队列。 */
    private void syncChildRuns(Backfill backfill) {
        List<BackfillRun> runs = backfillRunRepo.findByBackfillIdForUpdate(backfill.getId());
        Dag dag = dagRepo.findByIdAndTenantId(backfill.getDagId(), backfill.getTenantId()).orElse(null);
        for (BackfillRun backfillRun : runs) {
            if (backfillRun.getStatus() != BackfillRunStatus.RUNNING) {
                continue;
            }
            if (backfillRun.getJobRunId() == null) {
                backfillRun.setStatus(BackfillRunStatus.FAILED);
                backfillRun.setErrorMsg("Backfill child run was marked RUNNING before job_run was created");
                backfillRun.setUpdatedAt(Instant.now());
                backfillRunRepo.save(backfillRun);
                continue;
            }
            JobRun jobRun = orchestrationService.refreshRunStatusForBackfill(backfillRun.getJobRunId());
            jobRun = followAutomaticRetryChain(dag, backfillRun, jobRun);
            if (jobRun == null) {
                // 来源已失败但仍在等待 DAG 级重跑间隔/并发槽位，回填明细继续保持 RUNNING。
                continue;
            }
            BackfillRunStatus mapped = mapJobRunStatus(jobRun.getStatus());
            if (mapped == null) {
                continue;
            }
            backfillRun.setStatus(mapped);
            if (mapped == BackfillRunStatus.FAILED && !StringUtils.hasText(backfillRun.getErrorMsg())) {
                backfillRun.setErrorMsg("Job run failed: " + jobRun.getId());
            }
            backfillRun.setUpdatedAt(Instant.now());
            backfillRunRepo.save(backfillRun);
        }
        aggregateBackfill(backfill);
    }

    /**
     * 沿 retry_source_run_id 链切换到最新自动重跑；尚未派发但仍有次数时返回 null 表示等待。
     */
    private JobRun followAutomaticRetryChain(Dag dag, BackfillRun backfillRun, JobRun source) {
        JobRun current = source;
        Set<UUID> visited = new HashSet<>();
        while (current != null
                && current.getStatus() == DagStatus.FAILED
                && current.getId() != null
                && visited.add(current.getId())) {
            Optional<JobRun> retry = jobRunRepo.findFirstByRetrySourceRunIdOrderByStartedAtDesc(current.getId());
            if (retry.isEmpty()) {
                return automaticRetryPending(dag, current) ? null : current;
            }
            current = orchestrationService.refreshRunStatusForBackfill(retry.get().getId());
            backfillRun.setJobRunId(current.getId());
            backfillRun.setErrorMsg(null);
            backfillRun.setUpdatedAt(Instant.now());
            backfillRunRepo.save(backfillRun);
        }
        return current;
    }

    private boolean automaticRetryPending(Dag dag, JobRun failedRun) {
        if (dag == null || failedRun == null || failedRun.getRetryDispatchedAt() != null) {
            return false;
        }
        int retryLimit = Math.max(0, dag.getRunRetryCount() == null ? 0 : dag.getRunRetryCount());
        int attempt = Math.max(0,
                failedRun.getRunRetryAttempt() == null ? 0 : failedRun.getRunRetryAttempt());
        return attempt < retryLimit;
    }

    /**
     * 从全部明细重新计算成功数、失败数和批次聚合状态。
     *
     * <p>CANCELLED 为用户显式终态，不会被子运行统计覆盖；无失败且全部成功为
     * SUCCEEDED，成功与失败并存为 PARTIAL，全部失败为 FAILED。
     */
    private void aggregateBackfill(Backfill backfill) {
        List<BackfillRun> runs = backfillRunRepo.findByBackfillIdForUpdate(backfill.getId());
        long succeeded = runs.stream().filter(run -> run.getStatus() == BackfillRunStatus.SUCCEEDED).count();
        long failed = runs.stream().filter(run -> isFailedOrCancelled(run.getStatus())).count();
        long running = runs.stream().filter(run -> run.getStatus() == BackfillRunStatus.RUNNING).count();
        long queued = runs.stream().filter(run -> run.getStatus() == BackfillRunStatus.QUEUED).count();
        backfill.setSucceededRuns(Math.toIntExact(succeeded));
        backfill.setFailedRuns(Math.toIntExact(failed));
        backfill.setUpdatedAt(Instant.now());

        if (backfill.getStatus() != BackfillStatus.CANCELLED) {
            int totalRuns = backfill.getTotalRuns() == null ? 0 : backfill.getTotalRuns();
            if (totalRuns == 0) {
                backfill.setStatus(BackfillStatus.FAILED);
            } else if (queued == 0 && running == 0) {
                if (failed == 0 && succeeded == totalRuns) {
                    backfill.setStatus(BackfillStatus.SUCCEEDED);
                } else if (succeeded == 0) {
                    backfill.setStatus(BackfillStatus.FAILED);
                } else {
                    backfill.setStatus(BackfillStatus.PARTIAL);
                }
            } else if (running > 0 || succeeded > 0 || failed > 0) {
                backfill.setStatus(BackfillStatus.RUNNING);
            } else {
                backfill.setStatus(BackfillStatus.QUEUED);
            }
        }
        backfillRepo.save(backfill);
    }

    private boolean isFailedOrCancelled(BackfillRunStatus status) {
        return status == BackfillRunStatus.FAILED
                || status == BackfillRunStatus.CANCELLED;
    }

    private BackfillRunStatus mapJobRunStatus(DagStatus status) {
        if (status == DagStatus.SUCCEEDED) {
            return BackfillRunStatus.SUCCEEDED;
        }
        if (status == DagStatus.FAILED) {
            return BackfillRunStatus.FAILED;
        }
        if (status == DagStatus.CANCELLED) {
            return BackfillRunStatus.CANCELLED;
        }
        return null;
    }

    private List<BackfillWindow> expandWindows(Instant rangeStart,
                                               Instant rangeEnd,
                                               BackfillGrain grain,
                                               String timezone) {
        if (rangeStart == null || rangeEnd == null) {
            throw new BizException(40020, "rangeStart/rangeEnd 不能为空");
        }
        if (rangeStart.isAfter(rangeEnd)) {
            throw new BizException(40021, "rangeStart 不能晚于 rangeEnd");
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(StringUtils.hasText(timezone) ? timezone : "Asia/Shanghai");
        } catch (RuntimeException ex) {
            throw new BizException(40023, "timezone 非法: " + timezone, ex);
        }
        List<BackfillWindow> windows = new ArrayList<>();
        ZonedDateTime cursor = rangeStart.atZone(zoneId);
        while (!cursor.toInstant().isAfter(rangeEnd)) {
            if (windows.size() >= MAX_BACKFILL_RUNS) {
                throw new BizException(40022, "回填区间过大，最多支持 " + MAX_BACKFILL_RUNS + " 个业务日期");
            }
            ZonedDateTime next = grain.next(cursor);
            windows.add(new BackfillWindow(cursor.toInstant(), cursor.toInstant(), next.toInstant()));
            cursor = next;
        }
        return windows;
    }

    /** 将规划器区间转换为回填持久化窗口，并守住 logical_date == interval_start 契约。 */
    private BackfillWindow toBackfillWindow(DataIntervalCalculator.DataInterval interval) {
        if (interval == null
                || interval.logicalDate() == null
                || interval.dataIntervalStart() == null
                || interval.dataIntervalEnd() == null
                || !interval.logicalDate().equals(interval.dataIntervalStart())
                || !interval.dataIntervalEnd().isAfter(interval.dataIntervalStart())) {
            throw new BizException(40020, "catchup interval 非法");
        }
        return new BackfillWindow(
                interval.logicalDate(),
                interval.dataIntervalStart(),
                interval.dataIntervalEnd());
    }

    /**
     * 计算 DAG 级剩余并发槽位。
     *
     * <p>统计覆盖 CRON、MANUAL、BACKFILL 等所有触发来源，从而避免 catchup 子运行
     * 与刚创建的当前 CRON 周期叠加后超过 {@code dag.max_active_runs}。
     * 数据异常找不到 DAG 时退回批次上限，保留 M1 队列的可恢复性。
     */
    private int availableDagSlots(Backfill backfill, int fallbackLimit) {
        int dagLimit = dagRepo.findByIdAndTenantId(backfill.getDagId(), backfill.getTenantId())
                .map(Dag::getMaxActiveRuns)
                .map(value -> Math.max(1, value))
                .orElse(fallbackLimit);
        long activeRuns = jobRunRepo.countByDagIdAndStatusInAndRunModeNot(
                backfill.getDagId(),
                List.of(DagStatus.QUEUED, DagStatus.RUNNING),
                RunEnvironment.DEV.name());
        int available = Math.max(0, dagLimit - Math.toIntExact(Math.min(activeRuns, Integer.MAX_VALUE)));
        if (available == 0) {
            log.info("回填批次 {} 因 dag max_active_runs 限流，activeRuns={} limit={}",
                    backfill.getId(), activeRuns, dagLimit);
        }
        return available;
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required");
        }
        return tenantId;
    }

    private String resolveBackfillTimezone(Backfill backfill) {
        if (StringUtils.hasText(backfill.getTimezone())) {
            return backfill.getTimezone();
        }
        return dagRepo.findByIdAndTenantId(backfill.getDagId(), backfill.getTenantId())
                .map(Dag::getTimezone)
                .filter(StringUtils::hasText)
                .orElse("Asia/Shanghai");
    }

    private String currentActorName() {
        String username = TenantContext.getUsername();
        return StringUtils.hasText(username) ? username.trim() : "system";
    }

    private boolean isTerminal(BackfillStatus status) {
        return status == BackfillStatus.SUCCEEDED
                || status == BackfillStatus.FAILED
                || status == BackfillStatus.PARTIAL
                || status == BackfillStatus.CANCELLED;
    }

    private BackfillDTO toDTO(Backfill backfill, List<BackfillRun> runs) {
        return new BackfillDTO(
                backfill.getId(),
                backfill.getDagId(),
                backfill.getStatus().name(),
                backfill.getTotalRuns(),
                backfill.getSucceededRuns(),
                backfill.getFailedRuns(),
                backfill.getMaxParallel() == null ? 1 : backfill.getMaxParallel(),
                new BackfillDTO.Range(backfill.getRangeStart(), backfill.getRangeEnd()),
                backfill.getGrain(),
                resolveBackfillTimezone(backfill),
                backfill.getCreatedAt(),
                backfill.getUpdatedAt(),
                runs.stream().map(this::toRunDTO).toList());
    }

    private BackfillRunDTO toRunDTO(BackfillRun run) {
        return new BackfillRunDTO(
                run.getId(),
                run.getJobRunId(),
                run.getLogicalDate(),
                run.getDataIntervalStart(),
                run.getDataIntervalEnd(),
                run.getStatus().name(),
                run.getErrorMsg());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private enum BackfillGrain {
        HOUR {
            @Override
            ZonedDateTime next(ZonedDateTime dateTime) {
                return dateTime.plusHours(1);
            }
        },
        DAY {
            @Override
            ZonedDateTime next(ZonedDateTime dateTime) {
                return dateTime.plusDays(1);
            }
        },
        MONTH {
            @Override
            ZonedDateTime next(ZonedDateTime dateTime) {
                return dateTime.plusMonths(1);
            }
        };

        abstract ZonedDateTime next(ZonedDateTime dateTime);

        static BackfillGrain parse(String raw) {
            String value = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "DAY";
            try {
                return BackfillGrain.valueOf(value);
            } catch (IllegalArgumentException ex) {
                throw new BizException(40023, "grain 非法: " + raw);
            }
        }
    }

    private record BackfillWindow(
            Instant logicalDate,
            Instant dataIntervalStart,
            Instant dataIntervalEnd
    ) {}

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
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }
            if (userId != null) {
                TenantContext.setUserId(userId);
            }
            if (StringUtils.hasText(username)) {
                TenantContext.setUsername(username);
            }
            if (StringUtils.hasText(traceId) && !"-".equals(traceId)) {
                TenantContext.setTraceId(traceId);
            }
        }
    }
}
