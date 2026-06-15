package com.onelake.dataservice.repository;

import com.onelake.dataservice.domain.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByApiId(UUID apiId);
    List<Subscription> findBySubscriberId(UUID subscriberId);
    List<Subscription> findByApiIdAndStatus(UUID apiId, String status);
}
