package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 调度日历日期覆盖记录持久化接口。
 *
 * <p>主键类型为 {@link ScheduleCalendarDayId}。PipelineSchedulerService 与
 * CatchupPlanner 均通过 {@code findById(new ScheduleCalendarDayId(...))} 复用相同的
 * HOLIDAY 口径。
 */
public interface ScheduleCalendarDayRepository extends JpaRepository<ScheduleCalendarDay, ScheduleCalendarDayId> {
}
