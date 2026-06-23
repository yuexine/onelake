package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Operator;
import com.onelake.orchestration.domain.enums.OperatorScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    List<Operator> findByScopeAndTenantIdIsNull(OperatorScope scope);

    List<Operator> findByTenantIdAndScopeIn(UUID tenantId, Collection<OperatorScope> scopes);

    Optional<Operator> findByOperatorRefAndScopeAndTenantIdIsNull(String operatorRef, OperatorScope scope);

    Optional<Operator> findByTenantIdAndOperatorRefAndScopeIn(UUID tenantId, String operatorRef,
                                                              Collection<OperatorScope> scopes);

    List<Operator> findByOperatorRef(String operatorRef);
}
