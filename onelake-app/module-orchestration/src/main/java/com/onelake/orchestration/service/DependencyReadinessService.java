package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineDependency;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.domain.enums.ScheduleMode;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/** 按 logical_date 判定同周期和跨周期流水线上游是否已经就绪。 */
@Service
@RequiredArgsConstructor
public class DependencyReadinessService {

    private final PipelineDependencyRepository dependencyRepo;
    private final DagRepository dagRepo;
    private final JobRunRepository jobRunRepo;

    /** 调度器使用的布尔就绪门面。 */
    @Transactional(readOnly = true)
    public boolean isReady(Dag downstreamDag, Instant logicalDate) {
        return evaluate(downstreamDag, logicalDate).ready();
    }

    /** 无依赖或全部依赖满足时返回 ready；否则附带可观测的阻塞原因。 */
    @Transactional(readOnly = true)
    public ReadinessResult evaluate(Dag downstreamDag, Instant logicalDate) {
        if (downstreamDag == null || downstreamDag.getId() == null || logicalDate == null) {
            throw new IllegalArgumentException("下游流水线和 logicalDate 不能为空");
        }
        List<PipelineDependency> dependencies = dependencyRepo
                .findByDownstreamDagIdAndEnabledTrueOrderByCreatedAtAsc(downstreamDag.getId());
        if (dependencies.isEmpty()) {
            return ReadinessResult.readyResult();
        }

        List<DependencyBlocker> blockers = new ArrayList<>();
        for (PipelineDependency dependency : dependencies) {
            Dag upstreamDag = dagRepo.findByIdAndTenantId(
                            dependency.getUpstreamDagId(), downstreamDag.getTenantId())
                    .orElse(null);
            if (upstreamDag == null) {
                blockers.add(blocker(dependency, logicalDate, "UPSTREAM_NOT_FOUND"));
                continue;
            }

            Instant requiredLogicalDate;
            try {
                requiredLogicalDate = requiredLogicalDate(downstreamDag, dependency, logicalDate);
            } catch (IllegalArgumentException ex) {
                blockers.add(blocker(dependency, logicalDate, "INVALID_OFFSET"));
                continue;
            }

            ScheduleMode upstreamMode = ScheduleMode.from(upstreamDag.getScheduleMode());
            if (upstreamMode == ScheduleMode.FROZEN) {
                blockers.add(blocker(dependency, requiredLogicalDate, "UPSTREAM_FROZEN"));
                continue;
            }

            boolean succeeded = jobRunRepo.existsByDagIdAndLogicalDateAndStatusAndRunModeNot(
                    upstreamDag.getId(), requiredLogicalDate, DagStatus.SUCCEEDED,
                    RunEnvironment.DEV.name());
            DagStatus upstreamStatus = succeeded ? DagStatus.SUCCEEDED : null;
            if (!upstreamMode.satisfiesDependency(upstreamStatus)) {
                blockers.add(blocker(dependency, requiredLogicalDate, "WAITING_UPSTREAM"));
            }
        }
        return blockers.isEmpty()
                ? ReadinessResult.readyResult()
                : new ReadinessResult(false, List.copyOf(blockers));
    }

    private static DependencyBlocker blocker(PipelineDependency dependency,
                                              Instant requiredLogicalDate,
                                              String reason) {
        return new DependencyBlocker(
                dependency.getId(),
                dependency.getUpstreamDagId(),
                requiredLogicalDate,
                reason);
    }

    private static Instant requiredLogicalDate(Dag downstreamDag,
                                               PipelineDependency dependency,
                                               Instant downstreamLogicalDate) {
        String dependencyType = normalize(dependency.getDependencyType(), "SAME_CYCLE");
        if ("SAME_CYCLE".equals(dependencyType)) {
            return downstreamLogicalDate;
        }
        if (!"CROSS_CYCLE".equals(dependencyType)) {
            throw new IllegalArgumentException("不支持的 dependencyType: " + dependencyType);
        }

        String grain = normalize(dependency.getOffsetGrain(), null);
        if (!StringUtils.hasText(grain)) {
            throw new IllegalArgumentException("跨周期依赖缺少 offsetGrain");
        }
        int offset = dependency.getOffsetN() == null ? 0 : dependency.getOffsetN();
        try {
            ZoneId zone = ZoneId.of(StringUtils.hasText(downstreamDag.getTimezone())
                    ? downstreamDag.getTimezone()
                    : "UTC");
            ZonedDateTime local = downstreamLogicalDate.atZone(zone);
            return switch (grain) {
                case "HOUR" -> local.plusHours(offset).toInstant();
                case "DAY" -> local.plusDays(offset).toInstant();
                case "MONTH" -> local.plusMonths(offset).toInstant();
                default -> throw new IllegalArgumentException("不支持的 offsetGrain: " + grain);
            };
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("无效的流水线时区", ex);
        }
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : fallback;
    }

    /** 调度器可直接记录的依赖就绪结果。 */
    public record ReadinessResult(boolean ready, List<DependencyBlocker> blockers) {
        public static ReadinessResult readyResult() {
            return new ReadinessResult(true, List.of());
        }

        public String summary() {
            return blockers.stream()
                    .map(blocker -> blocker.upstreamDagId() + "@" + blocker.requiredLogicalDate()
                            + "[" + blocker.reason() + "]")
                    .collect(Collectors.joining(", "));
        }
    }

    /** 单条未满足依赖及其目标上游周期。 */
    public record DependencyBlocker(UUID dependencyId,
                                    UUID upstreamDagId,
                                    Instant requiredLogicalDate,
                                    String reason) {
    }
}
