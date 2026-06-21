package com.onelake.catalog.repository;

import com.onelake.catalog.domain.entity.LineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LineageEdgeRepository extends JpaRepository<LineageEdge, UUID> {

    List<LineageEdge> findByTenantIdAndUpstreamFqn(UUID tenantId, String fqn);

    List<LineageEdge> findByTenantIdAndDownstreamFqn(UUID tenantId, String fqn);

    Optional<LineageEdge> findByTenantIdAndUpstreamFqnAndDownstreamFqn(UUID tenantId, String upstreamFqn, String downstreamFqn);
}
