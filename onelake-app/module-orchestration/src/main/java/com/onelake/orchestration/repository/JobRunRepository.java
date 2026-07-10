package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.JobRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * DAG 运行实例持久化访问接口。
 */
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {
    Page<JobRun> findByDagIdOrderByStartedAtDesc(UUID dagId, Pageable pageable);

    Page<JobRun> findByDagIdInOrderByStartedAtDesc(Collection<UUID> dagIds, Pageable pageable);

    Page<JobRun> findByDagIdAndBackfillIdOrderByLogicalDateAsc(UUID dagId,
                                                               UUID backfillId,
                                                               Pageable pageable);

    Optional<JobRun> findByIdAndDagIdIn(UUID id, Collection<UUID> dagIds);

    Optional<JobRun> findByIdAndDagIdAndBackfillId(UUID id, UUID dagId, UUID backfillId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select jr from JobRun jr where jr.id = :id")
    Optional<JobRun> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select jr from JobRun jr where jr.id = :id and jr.dagId in :dagIds")
    Optional<JobRun> findByIdAndDagIdInForUpdate(@Param("id") UUID id,
                                                 @Param("dagIds") Collection<UUID> dagIds);

    Optional<JobRun> findFirstByDagIdOrderByStartedAtDesc(UUID dagId);
}
