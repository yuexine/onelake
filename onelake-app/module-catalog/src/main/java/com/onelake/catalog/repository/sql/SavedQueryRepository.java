package com.onelake.catalog.repository.sql;

import com.onelake.catalog.domain.entity.sql.SavedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedQueryRepository extends JpaRepository<SavedQuery, UUID> {

    List<SavedQuery> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    Optional<SavedQuery> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<SavedQuery> findByTenantIdAndName(UUID tenantId, String name);
}
