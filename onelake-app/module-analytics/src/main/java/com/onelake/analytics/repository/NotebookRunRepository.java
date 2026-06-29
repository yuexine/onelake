package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.NotebookRun;
import com.onelake.analytics.domain.enums.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotebookRunRepository extends JpaRepository<NotebookRun, UUID> {

    Optional<NotebookRun> findByIdAndTenantId(UUID id, UUID tenantId);

    List<NotebookRun> findByStatusIn(List<RunStatus> statuses);

    List<NotebookRun> findByNotebookIdOrderByCreatedAtDesc(UUID notebookId);
}
