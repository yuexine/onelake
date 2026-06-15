package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.SourceSchemaSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceSchemaSnapshotRepository extends JpaRepository<SourceSchemaSnapshot, UUID> {

    List<SourceSchemaSnapshot> findBySourceIdOrderByCapturedAtDesc(UUID sourceId);

    Optional<SourceSchemaSnapshot> findFirstBySourceIdAndObjectNameOrderByCapturedAtDesc(UUID sourceId, String objectName);
}
