package com.onelake.modeling.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.modeling.domain.entity.Metric;
import com.onelake.modeling.domain.entity.SubjectDomain;
import com.onelake.modeling.repository.MetricRepository;
import com.onelake.modeling.repository.SubjectDomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelingService {

    private final SubjectDomainRepository domainRepo;
    private final MetricRepository metricRepo;

    @Transactional
    public SubjectDomain createDomain(String code, String name, UUID parentId) {
        SubjectDomain d = new SubjectDomain();
        d.setTenantId(TenantContext.getTenantId());
        d.setCode(code);
        d.setName(name);
        d.setParentId(parentId);
        return domainRepo.save(d);
    }

    @Transactional(readOnly = true)
    public List<SubjectDomain> listDomains() {
        return domainRepo.findByTenantId(TenantContext.getTenantId());
    }

    @Transactional
    public Metric createMetric(Metric m) {
        m.setTenantId(TenantContext.getTenantId());
        if (m.getVersion() == null) m.setVersion(1);
        return metricRepo.save(m);
    }

    @Transactional(readOnly = true)
    public Metric getMetric(UUID id) {
        return metricRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "指标不存在"));
    }

    @Transactional(readOnly = true)
    public List<Metric> listMetricsByDomain(UUID domainId) {
        return metricRepo.findByDomainId(domainId);
    }
}
