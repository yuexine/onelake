package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.service.OrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orchestration/dags")
@RequiredArgsConstructor
public class DagController {

    private final OrchestrationService service;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DagDTO> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String dagsterJob = (String) body.get("dagsterJob");
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) body.get("definition");
        String cron = (String) body.get("scheduleCron");
        Boolean enabled = body.get("enabled") instanceof Boolean value ? value : null;
        return ApiResponse.ok(service.createDag(name, dagsterJob, definition, cron, enabled));
    }

    @GetMapping("/{id}")
    public ApiResponse<DagDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getDag(id));
    }

    @GetMapping
    public ApiResponse<List<DagDTO>> list() {
        return ApiResponse.ok(service.listDags());
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, Object>> run(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "MANUAL") String trigger) {
        UUID runId = service.triggerDag(id, TriggerType.valueOf(trigger.toUpperCase()));
        return ApiResponse.ok(Map.of("runId", runId));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<Page<JobRunDTO>> runs(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.runs(id, PageRequest.of(page, size)));
    }
}
