package com.onelake.catalog.repository;

import com.onelake.catalog.domain.entity.AssetConsumer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetConsumerRepository extends JpaRepository<AssetConsumer, UUID> {

    Optional<AssetConsumer> findByTenantIdAndAssetFqnAndConsumerTypeAndConsumerRef(
        UUID tenantId, String assetFqn, String consumerType, String consumerRef);

    /**
     * 影响分析：批量统计 scope 内某类型的活跃 consumer 数量。
     * 走部分索引 idx_asset_consumer_fqn (tenant_id, asset_fqn) WHERE status='ACTIVE'。
     */
    @Query("""
        SELECT COUNT(DISTINCT c.consumerRef)
        FROM AssetConsumer c
        WHERE c.tenantId = :tenantId
          AND c.consumerType = :consumerType
          AND c.status = 'ACTIVE'
          AND c.assetFqn IN :scope
        """)
    long countActiveByTypeAndFqnIn(
        @Param("tenantId") UUID tenantId,
        @Param("consumerType") String consumerType,
        @Param("scope") Collection<String> scope);

    /**
     * 列出 scope 内某类型的活跃 consumer（用于导出 / 通知）。
     */
    @Query("""
        SELECT c
        FROM AssetConsumer c
        WHERE c.tenantId = :tenantId
          AND c.consumerType = :consumerType
          AND c.status = 'ACTIVE'
          AND c.assetFqn IN :scope
        ORDER BY c.assetFqn, c.consumerName
        """)
    List<AssetConsumer> findActiveByTypeAndFqnIn(
        @Param("tenantId") UUID tenantId,
        @Param("consumerType") String consumerType,
        @Param("scope") Collection<String> scope);

    /**
     * 软删：把超出 scope 的旧记录置 REMOVED（用于全量回填/对账）。
     */
    @Modifying
    @Query("""
        UPDATE AssetConsumer c
        SET c.status = 'REMOVED', c.syncedAt = CURRENT_TIMESTAMP
        WHERE c.tenantId = :tenantId
          AND c.consumerType = :consumerType
          AND c.status = 'ACTIVE'
          AND c.consumerRef NOT IN :keepRefs
        """)
    int markRemovedExcept(
        @Param("tenantId") UUID tenantId,
        @Param("consumerType") String consumerType,
        @Param("keepRefs") Collection<String> keepRefs);
}
