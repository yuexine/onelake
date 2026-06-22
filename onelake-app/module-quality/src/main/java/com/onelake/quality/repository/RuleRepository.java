package com.onelake.quality.repository;

import com.onelake.quality.domain.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleRepository extends JpaRepository<Rule, UUID> {
    List<Rule> findByTenantId(UUID tenantId);
    List<Rule> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<Rule> findByTargetFqnAndEnabledTrue(String fqn);
    Optional<Rule> findFirstByTenantIdAndTargetFqnAndRuleTypeAndExpression(
        UUID tenantId,
        String targetFqn,
        String ruleType,
        String expression
    );
    List<Rule> findByTenantIdAndTargetFqnAndRuleTypeOrderByCreatedAtDesc(
        UUID tenantId,
        String targetFqn,
        String ruleType
    );
}
