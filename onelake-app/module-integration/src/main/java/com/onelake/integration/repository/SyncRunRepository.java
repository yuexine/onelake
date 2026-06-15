package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.SyncRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SyncRunRepository extends JpaRepository<SyncRun, UUID> {

    Page<SyncRun> findByTaskIdOrderByStartedAtDesc(UUID taskId, Pageable pageable);

    List<SyncRun> findByTaskId(UUID taskId);
}
