package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineDependencyWait;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 持久化依赖阻塞的计划点，供后续 scheduler tick 恢复。 */
public interface PipelineDependencyWaitRepository
        extends JpaRepository<PipelineDependencyWait, UUID> {

    /** 按首次阻塞顺序恢复等待计划点，优先处理更早的业务周期。 */
    List<PipelineDependencyWait> findAllByOrderByCreatedAtAscIdAsc();

    /** 同一 DAG/logical_date 只保留一条等待记录，多副本或重复 tick 可安全重试。 */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO orchestration.pipeline_dependency_wait
                (tenant_id, dag_id, logical_date, scheduled_at, created_at, updated_at)
            VALUES (:tenantId, :dagId, :logicalDate, :scheduledAt, now(), now())
            ON CONFLICT (dag_id, logical_date) DO UPDATE
            SET scheduled_at = EXCLUDED.scheduled_at,
                updated_at = now()
            """, nativeQuery = true)
    int enqueue(@Param("tenantId") UUID tenantId,
                @Param("dagId") UUID dagId,
                @Param("logicalDate") Instant logicalDate,
                @Param("scheduledAt") Instant scheduledAt);
}
