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

    /** 按业务周期返回批次全部明细，供进度展示和聚合。 */
    List<BackfillRun> findByBackfillIdOrderByLogicalDateAsc(UUID backfillId);

    /** 查询批次内确定 logical_date 的幂等明细。 */
    Optional<BackfillRun> findByBackfillIdAndLogicalDate(UUID backfillId, Instant logicalDate);

    /** 统计批次中单一状态的明细数量。 */
    long countByBackfillIdAndStatus(UUID backfillId, BackfillRunStatus status);

    /** 统计批次中一组状态的明细数量。 */
    long countByBackfillIdAndStatusIn(UUID backfillId, Collection<BackfillRunStatus> statuses);

    /** 锁定批次全部明细，供状态同步、进度聚合和取消操作获得一致视图。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select br from BackfillRun br where br.backfillId = :backfillId order by br.logicalDate")
    List<BackfillRun> findByBackfillIdForUpdate(@Param("backfillId") UUID backfillId);

    /**
     * 按 logical_date 顺序锁定一页指定状态明细。
     *
     * <p>Dispatcher 将页面大小设置为剩余并发槽位，因此一次事务只领取实际可派发数量。
     */
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
