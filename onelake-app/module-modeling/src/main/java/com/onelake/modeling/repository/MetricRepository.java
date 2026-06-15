package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.Metric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MetricRepository extends JpaRepository<Metric, UUID> {
    List<Metric> findByTenantId(UUID tenantId);
    List<Metric> findByDomainId(UUID domainId);
}
