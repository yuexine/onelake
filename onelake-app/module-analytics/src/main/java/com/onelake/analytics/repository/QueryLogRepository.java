package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.QueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface QueryLogRepository extends JpaRepository<QueryLog, UUID> {

    /**
     * 指定租户某日的查询量（用于配额统计）。
     */
    @Query("select count(q) from QueryLog q where q.tenantId = :tenantId and q.createdAt >= :start and q.createdAt < :end")
    long countByTenantAndRange(@Param("tenantId") UUID tenantId,
                               @Param("start") Instant start,
                               @Param("end") Instant end);

    /**
     * 指定数据集的慢查询（duration > threshold）记录。
     */
    List<QueryLog> findByDatasetIdAndDurationMsGreaterThanOrderByCreatedAtDesc(UUID datasetId, Integer threshold);
}
