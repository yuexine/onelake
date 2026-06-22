package com.onelake.dataservice.repository;

import com.onelake.dataservice.domain.entity.QuotaUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface QuotaUsageRepository extends JpaRepository<QuotaUsage, UUID> {
    Optional<QuotaUsage> findByAppKeyIdAndApiIdAndStatDate(UUID appKeyId, UUID apiId, LocalDate statDate);
}
