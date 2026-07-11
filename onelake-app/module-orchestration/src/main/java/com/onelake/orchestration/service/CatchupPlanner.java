package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.ScheduleCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计算当前 cron 周期之前尚未运行的历史数据区间。
 *
 * <p>调度器仍单独以 CRON 触发当前周期；本规划器只把严格早于当前计划点的缺口
 * 交给 M1 回填队列。最近一次 run 的 {@code data_interval_end} 是首选游标；没有历史
 * run（例如 DAG 创建后一直停用）时，从 DAG 创建时间开始扫描。
 */
@Component
@RequiredArgsConstructor
public class CatchupPlanner {

    /** 与 M1 单批回填上限保持一致，防止重新启用老 DAG 时一次展开过多周期。 */
    private static final int MAX_CATCHUP_WINDOWS = 10_000;

    /** 用于定位已覆盖到的最新业务周期。 */
    private final JobRunRepository jobRunRepo;
    /** 用于排除历史计划点中的节假日。 */
    private final ScheduleCalendarDayRepository calendarDayRepo;
    /** 把 cron 计划点转换成统一的 logical_date/data_interval。 */
    private final DataIntervalCalculator dataIntervalCalculator;

    /**
     * 规划当前计划点之前需要补跑的历史周期。
     *
     * <p>当前计划点不包含在返回结果中，它仍由 {@code PipelineSchedulerService}
     * 通过 CRON 路径触发，以继续使用 {@code (dag_id, logical_date)} 唯一幂等。
     * 返回窗口保持 cron 时间顺序，并已应用调度窗口和 HOLIDAY 过滤。
     *
     * @param dag 开启调度的流水线定义
     * @param currentScheduledAt 本轮 cron 命中的计划时刻
     * @return 需要交给 M1 回填派发器的历史周期计划；无需补跑时返回空计划
     */
    public CatchupPlan plan(Dag dag, Instant currentScheduledAt) {
        if (!Boolean.TRUE.equals(dag.getCatchup()) || currentScheduledAt == null) {
            return CatchupPlan.empty();
        }

        // 优先从最近已运行的业务周期继续；完全没有运行历史时，视为从 DAG 创建后恢复调度。
        Optional<JobRun> latestRun = jobRunRepo
                .findFirstByDagIdAndLogicalDateIsNotNullAndRunModeNotOrderByLogicalDateDesc(
                        dag.getId(), RunEnvironment.DEV.name());
        Instant cursor = latestRun
                .map(this::resumeAfter)
                .orElse(dag.getCreatedAt());
        // schedule_start 是闭区间边界。减 1ns 后再调用 CronExpression.next，
        // 可确保恰好落在 schedule_start 的计划点不会被 next() 的严格大于语义跳过。
        if (dag.getScheduleStart() != null && cursor != null
                && cursor.isBefore(dag.getScheduleStart())) {
            cursor = dag.getScheduleStart().minusNanos(1);
        }
        if (cursor == null || !cursor.isBefore(currentScheduledAt)) {
            return CatchupPlan.empty();
        }

        ZoneId zoneId = ZoneId.of(dag.getTimezone());
        CronExpression expression = CronExpression.parse(dag.getScheduleCron());
        ZonedDateTime current = currentScheduledAt.atZone(zoneId);
        ZonedDateTime candidate = expression.next(cursor.atZone(zoneId));
        Instant lastLogicalDate = latestRun.map(JobRun::getLogicalDate).orElse(null);
        List<DataIntervalCalculator.DataInterval> windows = new ArrayList<>();

        // candidate 严格早于 current：历史缺口进入回填，当前周期保留给 CRON 幂等路径。
        while (candidate != null && candidate.isBefore(current)) {
            if (isEligibleHistoricalPeriod(dag, candidate)) {
                DataIntervalCalculator.DataInterval interval = dataIntervalCalculator.calculate(
                        dag.getPartitionGrain(), candidate.toInstant(), dag.getTimezone());
                if (lastLogicalDate == null || interval.logicalDate().isAfter(lastLogicalDate)) {
                    if (windows.size() >= MAX_CATCHUP_WINDOWS) {
                        throw new IllegalStateException(
                                "catchup 展开超过上限 " + MAX_CATCHUP_WINDOWS + ": dagId=" + dag.getId());
                    }
                    windows.add(interval);
                }
            }
            candidate = expression.next(candidate);
        }
        return new CatchupPlan(windows);
    }

    /** 判断历史计划点是否仍处于有效调度窗口且不是绑定日历中的节假日。 */
    private boolean isEligibleHistoricalPeriod(Dag dag, ZonedDateTime scheduledAt) {
        Instant scheduledInstant = scheduledAt.toInstant();
        if ((dag.getScheduleStart() != null && scheduledInstant.isBefore(dag.getScheduleStart()))
                || (dag.getScheduleEnd() != null && scheduledInstant.isAfter(dag.getScheduleEnd()))) {
            return false;
        }
        if (dag.getCalendarId() == null) {
            return true;
        }
        return calendarDayRepo.findById(new ScheduleCalendarDayId(
                        dag.getCalendarId(), scheduledAt.toLocalDate()))
                .map(ScheduleCalendarDay::getDayType)
                .filter("HOLIDAY"::equalsIgnoreCase)
                .isEmpty();
    }

    /**
     * {@code data_interval_end} 即最近一次运行所覆盖周期的右边界，是下一次 cron 扫描的安全游标。
     * 兼容旧数据时，缺失区间右边界则退回 logical_date，后续过滤会去掉已覆盖周期。
     */
    private Instant resumeAfter(JobRun run) {
        return run.getDataIntervalEnd() != null ? run.getDataIntervalEnd() : run.getLogicalDate();
    }

    /**
     * 一次 catchup 规划结果。
     *
     * @param windows 已按计划时间升序排列的精确业务周期；构造时复制为不可变列表
     */
    public record CatchupPlan(List<DataIntervalCalculator.DataInterval> windows) {

        public CatchupPlan {
            windows = windows == null ? List.of() : List.copyOf(windows);
        }

        public static CatchupPlan empty() {
            return new CatchupPlan(List.of());
        }

        public boolean isEmpty() {
            return windows.isEmpty();
        }

        public int size() {
            return windows.size();
        }
    }
}
