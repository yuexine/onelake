package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.CodebookVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodebookVersionRepository extends JpaRepository<CodebookVersion, UUID> {
    List<CodebookVersion> findByTenantIdAndCodebookIdOrderByCreatedAtDesc(UUID tenantId, UUID codebookId);

    Optional<CodebookVersion> findByTenantIdAndCodebookIdAndVersionIgnoreCase(UUID tenantId, UUID codebookId, String version);

    long countByTenantIdAndCodebookId(UUID tenantId, UUID codebookId);
}
