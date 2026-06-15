package com.onelake.quality.repository;

import com.onelake.quality.domain.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RuleRepository extends JpaRepository<Rule, UUID> {
    List<Rule> findByTenantId(UUID tenantId);
    List<Rule> findByTargetFqnAndEnabledTrue(String fqn);
}
