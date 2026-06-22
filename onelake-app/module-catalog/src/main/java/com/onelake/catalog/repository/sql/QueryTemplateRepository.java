package com.onelake.catalog.repository.sql;

import com.onelake.catalog.domain.entity.sql.QueryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QueryTemplateRepository extends JpaRepository<QueryTemplate, UUID> {

    List<QueryTemplate> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    Optional<QueryTemplate> findByTenantIdAndName(UUID tenantId, String name);

    Optional<QueryTemplate> findByTenantIdAndId(UUID tenantId, UUID id);
}
