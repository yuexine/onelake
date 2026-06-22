package com.onelake.common.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
