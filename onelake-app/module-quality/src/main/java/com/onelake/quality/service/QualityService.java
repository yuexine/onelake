package com.onelake.quality.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.quality.domain.entity.Alert;
import com.onelake.quality.domain.entity.Rule;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.repository.AlertRepository;
import com.onelake.quality.repository.RuleRepository;
import com.onelake.quality.repository.RunResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QualityService {

    private final RuleRepository ruleRepo;
    private final RunResultRepository resultRepo;
    private final AlertRepository alertRepo;

    @Transactional
    public Rule createRule(Rule r) {
        r.setTenantId(TenantContext.getTenantId());
        if (r.getVersion() == null) r.setVersion(1);
        if (r.getSeverity() == null) r.setSeverity("BLOCK");
        if (r.getEnabled() == null) r.setEnabled(true);
        return ruleRepo.save(r);
    }

    @Transactional(readOnly = true)
    public Rule getRule(UUID id) {
        return ruleRepo.findById(id).orElseThrow(() -> new BizException(40400, "规则不存在"));
    }

    @Transactional(readOnly = true)
    public List<Rule> listRules() {
        return ruleRepo.findByTenantId(TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public List<Rule> rulesFor(String targetFqn) {
        return ruleRepo.findByTargetFqnAndEnabledTrue(targetFqn);
    }

    @Transactional
    public RunResult recordResult(RunResult r) {
        return resultRepo.save(r);
    }

    @Transactional(readOnly = true)
    public List<RunResult> recentResults(UUID ruleId) {
        return resultRepo.findByRuleIdOrderByCheckedAtDesc(ruleId);
    }

    @Transactional
    public Alert raiseAlert(UUID ruleId, String level, String message) {
        Alert a = new Alert();
        a.setTenantId(TenantContext.getTenantId());
        a.setRuleId(ruleId);
        a.setLevel(level);
        a.setMessage(message);
        a.setStatus("OPEN");
        return alertRepo.save(a);
    }

    @Transactional(readOnly = true)
    public List<Alert> openAlerts() {
        return alertRepo.findByTenantIdAndStatus(TenantContext.getTenantId(), "OPEN");
    }

    @Transactional
    public void closeAlert(UUID id) {
        Alert a = alertRepo.findById(id).orElseThrow(() -> new BizException(40400, "告警不存在"));
        a.setStatus("CLOSED");
    }
}
