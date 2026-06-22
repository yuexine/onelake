package com.onelake.common.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunningTaskRepository extends JpaRepository<RunningTask, UUID> {

    Optional<RunningTask> findByTenantIdAndRefTypeAndRefId(UUID tenantId, String refType, String refId);

    Optional<RunningTask> findByTenantIdAndId(UUID tenantId, UUID id);

    @Query("""
        select t from RunningTask t
        where t.tenantId = :tenantId
          and t.dismissedAt is null
          and (
            t.status in :activeStatuses
            or (:includeRecent = true and (t.status = 'FAILED' or t.finishedAt >= :recentAfter))
          )
        order by t.updatedAt desc
        """)
    List<RunningTask> findVisible(
        @Param("tenantId") UUID tenantId,
        @Param("activeStatuses") Collection<String> activeStatuses,
        @Param("includeRecent") boolean includeRecent,
        @Param("recentAfter") Instant recentAfter,
        Pageable pageable
    );
}
