package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ScheduleCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 调度日历主对象持久化接口。
 *
 * <p>提供日历配置的标准 CRUD；计划点是否为 HOLIDAY 由
 * {@link ScheduleCalendarDayRepository} 按复合主键查询。
 */
public interface ScheduleCalendarRepository extends JpaRepository<ScheduleCalendar, UUID> {

    /** 按名称列出当前租户可绑定的日历。 */
    List<ScheduleCalendar> findByTenantIdOrderByNameAsc(UUID tenantId);

    /** 在租户边界内校验日历绑定。 */
    Optional<ScheduleCalendar> findByIdAndTenantId(UUID id, UUID tenantId);
}
