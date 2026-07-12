package com.onelake.quality.repository;

import com.onelake.quality.domain.entity.RunResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunResultRepository extends JpaRepository<RunResult, UUID> {
    List<RunResult> findByRuleIdOrderByCheckedAtDesc(UUID ruleId);
    List<RunResult> findTop1ByRuleIdOrderByCheckedAtDesc(UUID ruleId);
    List<RunResult> findByJobRunIdAndRuleIdIn(UUID jobRunId, List<UUID> ruleIds);
}
