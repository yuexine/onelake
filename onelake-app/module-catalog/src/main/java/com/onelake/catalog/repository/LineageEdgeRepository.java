package com.onelake.catalog.repository;

import com.onelake.catalog.domain.entity.LineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LineageEdgeRepository extends JpaRepository<LineageEdge, UUID> {

    List<LineageEdge> findByTenantIdAndUpstreamFqn(UUID tenantId, String fqn);

    List<LineageEdge> findByTenantIdAndDownstreamFqn(UUID tenantId, String fqn);

    Optional<LineageEdge> findByTenantIdAndUpstreamFqnAndDownstreamFqn(UUID tenantId, String upstreamFqn, String downstreamFqn);

    /**
     * 血缘图下游 BFS 批量拉边（depth 展开时使用，避免逐节点 N+1）。
     */
    List<LineageEdge> findByTenantIdAndUpstreamFqnIn(UUID tenantId, Collection<String> upstreamFqns);

    /**
     * 血缘图上游 BFS 批量拉边。
     */
    List<LineageEdge> findByTenantIdAndDownstreamFqnIn(UUID tenantId, Collection<String> downstreamFqns);
}
