package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Backfill;
import com.onelake.orchestration.domain.enums.BackfillStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 真回填批次持久化访问接口。
 */
public interface BackfillRepository extends JpaRepository<Backfill, UUID> {

    /** 查询需要 Dispatcher 恢复处理的批次，并按创建时间保证老批次优先。 */
    List<Backfill> findByStatusInOrderByCreatedAtAsc(Collection<BackfillStatus> statuses);

    /** 查询某 DAG 的回填历史，供 API 展示。 */
    List<Backfill> findByDagIdOrderByCreatedAtDesc(UUID dagId);

    /** 在租户边界内读取批次，防止跨租户访问。 */
    Optional<Backfill> findByIdAndTenantId(UUID id, UUID tenantId);

    /** 在租户边界内锁定批次，供取消操作与派发状态变更串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bf from Backfill bf where bf.id = :id and bf.tenantId = :tenantId")
    Optional<Backfill> findByIdAndTenantIdForUpdate(@Param("id") UUID id,
                                                    @Param("tenantId") UUID tenantId);

    /** 锁定待派发批次；同一批次只允许一个事务计算槽位并领取明细。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bf from Backfill bf where bf.id = :id")
    Optional<Backfill> findByIdForUpdate(@Param("id") UUID id);
}
