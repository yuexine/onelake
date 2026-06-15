package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.domain.entity.CdcTask;
import com.onelake.integration.dto.CdcStatusDTO;
import com.onelake.integration.service.CdcTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integration/cdc-tasks")
@RequiredArgsConstructor
public class CdcTaskController {

    private final CdcTaskService service;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.create(UUID.fromString(body.get("sourceId")), body.get("tableName")));
    }

    @GetMapping
    public ApiResponse<List<CdcTask>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<CdcTask> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> start(@PathVariable UUID id) {
        return ApiResponse.ok(service.start(id));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<CdcTask> stop(@PathVariable UUID id) {
        return ApiResponse.ok(service.pause(id));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<CdcStatusDTO> status(@PathVariable UUID id) {
        return ApiResponse.ok(service.status(id));
    }
}
