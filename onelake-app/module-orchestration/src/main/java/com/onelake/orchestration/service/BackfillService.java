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
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.BackfillRunDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.BackfillRepository;
import com.onelake.orchestration.repository.BackfillRunRepository;
import com.onelake.orchestration.repository.DagRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Scheduler-managed 业务日期区间回填服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillService {

    private static final int MAX_BACKFILL_RUNS = 10_000;

    private final BackfillRepository backfillRepo;
    private final BackfillRunRepository backfillRunRepo;
    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;

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

        Backfill backfill = new Backfill();
        backfill.setTenantId(tenantId);
        backfill.setDagId(dagId);
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
            run.setDagId(dagId);
            run.setLogicalDate(window.logicalDate());
            run.setDataIntervalStart(window.dataIntervalStart());
            run.setDataIntervalEnd(window.dataIntervalEnd());
            run.setStatus(BackfillRunStatus.QUEUED);
            plannedRuns.add(run);
        }
        backfillRunRepo.saveAll(plannedRuns);
        log.info("已创建回填批次 dagId={} backfillId={} grain={} totalRuns={} maxParallel={}",
                dagId, backfill.getId(), normalizedGrain, plannedRuns.size(), backfill.getMaxParallel());
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

    @Transactional(noRollbackFor = BizException.class)
    public int dispatchBackfill(UUID backfillId) {
        Backfill backfill = backfillRepo.findByIdForUpdate(backfillId)
                .orElseThrow(() -> new BizException(40400, "回填批次不存在"));
        if (backfill.getStatus() == BackfillStatus.CANCELLED || isTerminal(backfill.getStatus())) {
            return 0;
        }

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
            syncChildRuns(backfill);
            if (isTerminal(backfill.getStatus()) || backfill.getStatus() == BackfillStatus.CANCELLED) {
                return 0;
            }

            long running = backfillRunRepo.countByBackfillIdAndStatus(backfill.getId(), BackfillRunStatus.RUNNING);
            int maxParallel = Math.max(1, backfill.getMaxParallel() == null ? 1 : backfill.getMaxParallel());
            int slots = Math.max(0, maxParallel - Math.toIntExact(Math.min(running, Integer.MAX_VALUE)));
            if (slots == 0) {
                aggregateBackfill(backfill);
                return 0;
            }

            List<BackfillRun> queuedRuns = backfillRunRepo.findByBackfillIdAndStatusForUpdate(
                    backfill.getId(), BackfillRunStatus.QUEUED, PageRequest.of(0, slots));
            String timezone = resolveBackfillTimezone(backfill);
            int dispatched = 0;
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

    private void syncChildRuns(Backfill backfill) {
        List<BackfillRun> runs = backfillRunRepo.findByBackfillIdForUpdate(backfill.getId());
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
