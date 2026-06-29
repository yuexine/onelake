package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.JobRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface JobRunRepository extends JpaRepository<JobRun, UUID> {
    Page<JobRun> findByDagIdOrderByStartedAtDesc(UUID dagId, Pageable pageable);

    Page<JobRun> findByDagIdInOrderByStartedAtDesc(Collection<UUID> dagIds, Pageable pageable);

    Optional<JobRun> findByIdAndDagIdIn(UUID id, Collection<UUID> dagIds);

    Optional<JobRun> findFirstByDagIdOrderByStartedAtDesc(UUID dagId);
}
