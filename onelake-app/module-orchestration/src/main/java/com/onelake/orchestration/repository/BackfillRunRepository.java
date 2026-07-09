package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.BackfillRun;
import com.onelake.orchestration.domain.enums.BackfillRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 回填业务日期明细持久化访问接口。
 */
public interface BackfillRunRepository extends JpaRepository<BackfillRun, UUID> {
    List<BackfillRun> findByBackfillIdOrderByLogicalDateAsc(UUID backfillId);

    Optional<BackfillRun> findByBackfillIdAndLogicalDate(UUID backfillId, Instant logicalDate);

    long countByBackfillIdAndStatus(UUID backfillId, BackfillRunStatus status);

    long countByBackfillIdAndStatusIn(UUID backfillId, Collection<BackfillRunStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select br from BackfillRun br where br.backfillId = :backfillId order by br.logicalDate")
    List<BackfillRun> findByBackfillIdForUpdate(@Param("backfillId") UUID backfillId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select br
            from BackfillRun br
            where br.backfillId = :backfillId
              and br.status = :status
            order by br.logicalDate
            """)
    List<BackfillRun> findByBackfillIdAndStatusForUpdate(@Param("backfillId") UUID backfillId,
                                                         @Param("status") BackfillRunStatus status,
                                                         Pageable pageable);
}
