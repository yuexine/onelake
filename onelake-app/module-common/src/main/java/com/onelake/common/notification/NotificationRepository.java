package com.onelake.common.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByTenantIdAndReceiverIdOrderByCreatedAtDesc(
        UUID tenantId,
        UUID receiverId,
        Pageable pageable
    );

    Optional<Notification> findByTenantIdAndReceiverIdAndId(UUID tenantId, UUID receiverId, UUID id);

    List<Notification> findByTenantIdAndReceiverIdAndIsReadFalse(UUID tenantId, UUID receiverId);

    Optional<Notification> findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
        UUID tenantId,
        UUID receiverId,
        String sourceRefType,
        String sourceRefId
    );

    /** Atomically insert an orchestration-node notification and ignore an idempotent retry. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO common.notification (
            id, tenant_id, receiver_id, category, title, content, link, level,
            is_read, source_ref_type, source_ref_id, created_at
        ) VALUES (
            :id, :tenantId, :receiverId, 'PIPELINE', :title, :content, :link, :level,
            false, 'PIPELINE_NODE', :sourceRefId, :createdAt
        )
        ON CONFLICT (tenant_id, receiver_id, source_ref_type, source_ref_id)
            WHERE source_ref_type IS NOT NULL AND source_ref_id IS NOT NULL
        DO NOTHING
        """, nativeQuery = true)
    int insertPipelineNodeNotification(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("receiverId") UUID receiverId,
            @Param("title") String title,
            @Param("content") String content,
            @Param("link") String link,
            @Param("level") String level,
            @Param("sourceRefId") String sourceRefId,
            @Param("createdAt") Instant createdAt);
}
