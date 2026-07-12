package com.onelake.quality.repository;

import com.onelake.quality.domain.entity.Rule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

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

    /** 串行汇总同一资产/run 的规则结果，避免并发完成时遗漏最终质量事件。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Rule> findByTenantIdAndTargetFqnAndEnabledTrueOrderByIdAsc(
        UUID tenantId,
        String targetFqn
    );
}
