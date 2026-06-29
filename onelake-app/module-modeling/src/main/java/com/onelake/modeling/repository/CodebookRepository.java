package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.Codebook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodebookRepository extends JpaRepository<Codebook, UUID> {
    Optional<Codebook> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Codebook> findByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);

    List<Codebook> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
