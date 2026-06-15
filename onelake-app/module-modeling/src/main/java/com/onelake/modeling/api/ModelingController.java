package com.onelake.modeling.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.exception.BizException;
import com.onelake.modeling.domain.entity.Metric;
import com.onelake.modeling.domain.entity.SubjectDomain;
import com.onelake.modeling.service.ModelingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/modeling")
@RequiredArgsConstructor
public class ModelingController {

    private final ModelingService service;

    @PostMapping("/domains")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SubjectDomain> createDomain(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createDomain(
            (String) body.get("code"),
            (String) body.get("name"),
            body.get("parentId") == null ? null : UUID.fromString(body.get("parentId").toString())));
    }

    @GetMapping("/domains")
    public ApiResponse<List<SubjectDomain>> listDomains() {
        return ApiResponse.ok(service.listDomains());
    }

    @PostMapping("/metrics")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Metric> createMetric(@RequestBody Metric body) {
        if (body.getCode() == null || body.getName() == null || body.getMetricType() == null) {
            throw new BizException(40001, "code/name/metricType 必填");
        }
        return ApiResponse.ok(service.createMetric(body));
    }

    @GetMapping("/metrics/{id}")
    public ApiResponse<Metric> getMetric(@PathVariable UUID id) {
        return ApiResponse.ok(service.getMetric(id));
    }

    @GetMapping("/metrics/by-domain/{domainId}")
    public ApiResponse<List<Metric>> listByDomain(@PathVariable UUID domainId) {
        return ApiResponse.ok(service.listMetricsByDomain(domainId));
    }
}
