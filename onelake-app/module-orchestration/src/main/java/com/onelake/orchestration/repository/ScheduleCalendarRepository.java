package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ScheduleCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 调度日历主对象持久化接口。
 *
 * <p>提供日历配置的标准 CRUD；计划点是否为 HOLIDAY 由
 * {@link ScheduleCalendarDayRepository} 按复合主键查询。
 */
public interface ScheduleCalendarRepository extends JpaRepository<ScheduleCalendar, UUID> {
}
