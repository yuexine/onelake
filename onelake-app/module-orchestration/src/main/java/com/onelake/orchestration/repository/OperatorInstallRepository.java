package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.OperatorInstall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorInstallRepository extends JpaRepository<OperatorInstall, UUID> {

    List<OperatorInstall> findByTenantId(UUID tenantId);

    Optional<OperatorInstall> findByTenantIdAndOperatorId(UUID tenantId, UUID operatorId);
}
