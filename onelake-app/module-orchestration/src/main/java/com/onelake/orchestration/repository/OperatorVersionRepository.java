package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.OperatorVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorVersionRepository extends JpaRepository<OperatorVersion, UUID> {

    Optional<OperatorVersion> findByOperatorIdAndVersion(UUID operatorId, String version);

    List<OperatorVersion> findByOperatorIdOrderByCreatedAtDesc(UUID operatorId);
}
