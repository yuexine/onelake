package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ScheduleCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 调度日历持久化访问接口。
 */
public interface ScheduleCalendarRepository extends JpaRepository<ScheduleCalendar, UUID> {
}
