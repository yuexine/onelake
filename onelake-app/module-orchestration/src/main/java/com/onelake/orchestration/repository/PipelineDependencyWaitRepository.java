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

    /** 按首次阻塞顺序恢复仍处于 WAITING 的计划点。 */
    List<PipelineDependencyWait> findByStatusOrderByCreatedAtAscIdAsc(String status);

    /** 调度抽屉展示最近 100 条等待及终态审计记录。 */
    List<PipelineDependencyWait> findTop100ByDagIdAndTenantIdOrderByCreatedAtDesc(
            UUID dagId, UUID tenantId);

    /** 同一 DAG/logical_date 只保留一条等待记录，多副本或重复 tick 可安全重试。 */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO orchestration.pipeline_dependency_wait
                (tenant_id, dag_id, pipeline_version_id, logical_date, scheduled_at,
                 wait_reason, status, last_blockers, expires_at, created_at, updated_at)
            VALUES (:tenantId, :dagId, :pipelineVersionId, :logicalDate, :scheduledAt, :waitReason, 'WAITING',
                    :lastBlockers, :expiresAt, now(), now())
            ON CONFLICT (dag_id, logical_date) DO UPDATE
            SET pipeline_version_id = EXCLUDED.pipeline_version_id,
                scheduled_at = EXCLUDED.scheduled_at,
                wait_reason = EXCLUDED.wait_reason,
                last_blockers = EXCLUDED.last_blockers,
                expires_at = LEAST(orchestration.pipeline_dependency_wait.expires_at,
                                   EXCLUDED.expires_at),
                updated_at = now()
            WHERE orchestration.pipeline_dependency_wait.status = 'WAITING'
            """, nativeQuery = true)
    int enqueue(@Param("tenantId") UUID tenantId,
                @Param("dagId") UUID dagId,
                @Param("pipelineVersionId") UUID pipelineVersionId,
                @Param("logicalDate") Instant logicalDate,
                @Param("scheduledAt") Instant scheduledAt,
                @Param("waitReason") String waitReason,
                @Param("lastBlockers") String lastBlockers,
                @Param("expiresAt") Instant expiresAt);

    @Transactional
    @Modifying
    @Query("""
            update PipelineDependencyWait w
               set w.lastBlockers = :lastBlockers,
                   w.updatedAt = :updatedAt
             where w.id = :id and w.status = 'WAITING'
            """)
    int updateBlockers(@Param("id") UUID id,
                       @Param("lastBlockers") String lastBlockers,
                       @Param("updatedAt") Instant updatedAt);

    @Transactional
    @Modifying
    @Query("""
            update PipelineDependencyWait w
               set w.status = :status,
                   w.lastBlockers = :detail,
                   w.resolvedAt = :resolvedAt,
                   w.updatedAt = :resolvedAt
             where w.id = :id and w.status = 'WAITING'
            """)
    int finish(@Param("id") UUID id,
               @Param("status") String status,
               @Param("detail") String detail,
               @Param("resolvedAt") Instant resolvedAt);
}
