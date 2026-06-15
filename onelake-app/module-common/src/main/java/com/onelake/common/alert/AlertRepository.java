package com.onelake.common.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
    List<Alert> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
