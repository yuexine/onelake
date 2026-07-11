package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.dto.DagSchedulingDTO;
import com.onelake.orchestration.dto.ScheduleCalendarDTO;
import com.onelake.orchestration.dto.ScheduleWaitDTO;
import com.onelake.orchestration.dto.UpdateDagSchedulingRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.ScheduleCalendarRepository;
import com.onelake.orchestration.repository.PipelineDependencyWaitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 流水线调度字段与租户调度日历的管理服务。 */
@Service
@RequiredArgsConstructor
public class PipelineSchedulingService {

    private static final Set<String> SCHEDULE_MODES = Set.of("NORMAL", "DRY_RUN", "FROZEN");
    private static final Set<String> MISFIRE_POLICIES = Set.of("FIRE_ONCE", "SKIP");

    private final DagRepository dagRepo;
    private final ScheduleCalendarRepository calendarRepo;
    private final PipelineDependencyWaitRepository scheduleWaitRepo;

    /** 读取当前租户内流水线的完整生产调度策略。 */
    @Transactional(readOnly = true)
    public DagSchedulingDTO getScheduling(UUID dagId) {
        return DagSchedulingDTO.of(requireDag(dagId, requireTenant()));
    }

    /** 列出当前租户可绑定的调度日历。 */
    @Transactional(readOnly = true)
    public List<ScheduleCalendarDTO> listCalendars() {
        return calendarRepo.findByTenantIdOrderByNameAsc(requireTenant()).stream()
                .map(ScheduleCalendarDTO::of)
                .toList();
    }

    /** 查询指定流水线最近的调度等待记录，包含已解决、超时和取消终态。 */
    @Transactional(readOnly = true)
    public List<ScheduleWaitDTO> listScheduleWaits(UUID dagId) {
        UUID tenantId = requireTenant();
        requireDag(dagId, tenantId);
        return scheduleWaitRepo
                .findTop100ByDagIdAndTenantIdOrderByCreatedAtDesc(dagId, tenantId)
                .stream()
                .map(ScheduleWaitDTO::of)
                .toList();
    }

    /** 校验并原子更新生产调度字段，不改动 DAG 定义版本与执行拓扑。 */
    @Transactional
    public DagSchedulingDTO updateScheduling(UUID dagId, UpdateDagSchedulingRequest request) {
        UUID tenantId = requireTenant();
        Dag dag = requireDag(dagId, tenantId);
        if (request == null) {
            throw new BizException(40020, "调度配置不能为空");
        }

        String timezone = requireTimezone(request.timezone());
        int maxActiveRuns = requireRange(request.maxActiveRuns(), 1, 100, "maxActiveRuns");
        int priority = requireRange(request.priority(), 0, 100, "priority");
        String scheduleMode = normalizeMode(request.scheduleMode());
        String misfirePolicy = request.misfirePolicy() == null
                ? normalizeMisfirePolicy(dag.getMisfirePolicy())
                : normalizeMisfirePolicy(request.misfirePolicy());
        int dependencyWaitTimeoutMinutes = request.dependencyWaitTimeoutMinutes() == null
                ? Math.max(1, dag.getDependencyWaitTimeoutMinutes() == null
                        ? 1440 : dag.getDependencyWaitTimeoutMinutes())
                : requireRange(request.dependencyWaitTimeoutMinutes(), 1, 43_200,
                        "dependencyWaitTimeoutMinutes");
        requirePositive(request.slaMinutes(), "slaMinutes");
        requirePositive(request.timeoutMinutes(), "timeoutMinutes");
        int runRetryCount = request.runRetryCount() == null
                ? Math.max(0, dag.getRunRetryCount() == null ? 0 : dag.getRunRetryCount())
                : requireRange(request.runRetryCount(), 0, 100, "runRetryCount");
        int runRetryIntervalSeconds = request.runRetryIntervalSeconds() == null
                ? Math.max(0, dag.getRunRetryIntervalSeconds() == null ? 0 : dag.getRunRetryIntervalSeconds())
                : requireRange(request.runRetryIntervalSeconds(), 0, 604_800, "runRetryIntervalSeconds");
        if (request.scheduleStart() != null && request.scheduleEnd() != null
                && request.scheduleEnd().isBefore(request.scheduleStart())) {
            throw new BizException(40024, "scheduleEnd 不能早于 scheduleStart");
        }
        if (request.calendarId() != null
                && calendarRepo.findByIdAndTenantId(request.calendarId(), tenantId).isEmpty()) {
            throw new BizException(40025, "调度日历不存在或不属于当前租户");
        }

        int updated = dagRepo.updateSchedulingPolicy(
                dagId,
                tenantId,
                timezone,
                Boolean.TRUE.equals(request.catchup()),
                maxActiveRuns,
                priority,
                scheduleMode,
                misfirePolicy,
                dependencyWaitTimeoutMinutes,
                request.slaMinutes(),
                request.timeoutMinutes(),
                runRetryCount,
                runRetryIntervalSeconds,
                request.calendarId(),
                request.scheduleStart(),
                request.scheduleEnd());
        if (updated != 1) {
            throw new BizException(40400, "Pipeline 不存在");
        }
        dagRepo.markPublishedDagChanged(dagId, tenantId);
        return DagSchedulingDTO.of(requireDag(dagId, tenantId));
    }

    private Dag requireDag(UUID dagId, UUID tenantId) {
        return dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required");
        }
        return tenantId;
    }

    private String requireTimezone(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(40021, "timezone 不能为空");
        }
        String timezone = value.trim();
        try {
            ZoneId.of(timezone);
            return timezone;
        } catch (DateTimeException ex) {
            throw new BizException(40021, "无效的 timezone: " + timezone, ex);
        }
    }

    private int requireRange(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) {
            throw new BizException(40022, field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private String normalizeMode(String value) {
        String mode = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "NORMAL";
        if (!SCHEDULE_MODES.contains(mode)) {
            throw new BizException(40023, "scheduleMode 仅支持 NORMAL、DRY_RUN 或 FROZEN");
        }
        return mode;
    }

    private String normalizeMisfirePolicy(String value) {
        String policy = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "FIRE_ONCE";
        if (!MISFIRE_POLICIES.contains(policy)) {
            throw new BizException(40023, "misfirePolicy 仅支持 FIRE_ONCE 或 SKIP");
        }
        return policy;
    }

    private void requirePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new BizException(40022, field + " 必须大于 0");
        }
    }
}
