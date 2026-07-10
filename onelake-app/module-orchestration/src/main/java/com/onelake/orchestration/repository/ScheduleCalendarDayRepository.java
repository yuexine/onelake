package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 调度日历日期持久化访问接口。
 */
public interface ScheduleCalendarDayRepository extends JpaRepository<ScheduleCalendarDay, ScheduleCalendarDayId> {
}
