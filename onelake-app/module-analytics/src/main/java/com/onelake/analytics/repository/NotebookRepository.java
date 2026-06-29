package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.Notebook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotebookRepository extends JpaRepository<Notebook, UUID> {

    Optional<Notebook> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Notebook> findByTenantId(UUID tenantId);
}
