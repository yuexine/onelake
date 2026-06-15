package com.onelake.security.repository;

import com.onelake.security.domain.entity.PiiScanRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PiiScanRecordRepository extends JpaRepository<PiiScanRecord, UUID> {

    List<PiiScanRecord> findByTenantIdOrderByScannedAtDesc(UUID tenantId);

    List<PiiScanRecord> findByTenantIdAndStatus(UUID tenantId, PiiScanRecord.Status status);
}
