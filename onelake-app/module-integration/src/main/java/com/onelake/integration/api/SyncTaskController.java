package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.service.SyncTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integration/sync-tasks")
@RequiredArgsConstructor
public class SyncTaskController {

    private final SyncTaskService service;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> create(@Valid @RequestBody CreateSyncTaskVO vo) {
        return ApiResponse.ok(service.create(vo));
    }

    @GetMapping("/{id}")
    public ApiResponse<SyncTaskDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping
    public ApiResponse<List<SyncTaskDTO>> list(@RequestParam(required = false) UUID sourceId,
                                               @RequestParam(required = false) String mode,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(sourceId, mode, status, keyword));
    }

    @GetMapping("/by-source/{sourceId}")
    public ApiResponse<List<SyncTaskDTO>> listBySource(@PathVariable UUID sourceId) {
        return ApiResponse.ok(service.listBySource(sourceId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> update(@PathVariable UUID id,
                                           @RequestBody UpdateSyncTaskVO vo) {
        return ApiResponse.ok(service.update(id, vo));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> enable(@PathVariable UUID id) {
        return ApiResponse.ok(service.enable(id));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<SyncTaskDTO> disable(@PathVariable UUID id) {
        return ApiResponse.ok(service.disable(id));
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<java.util.Map<String, Object>> run(@PathVariable UUID id) {
        UUID runId = service.trigger(id);
        return ApiResponse.ok(java.util.Map.of("runId", runId));
    }

    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<java.util.Map<String, Object>> trigger(@PathVariable UUID id) {
        UUID runId = service.trigger(id);
        return ApiResponse.ok(java.util.Map.of("runId", runId));
    }

    @PostMapping("/runs/{runId}/reconcile")
    @PreAuthorize("hasRole('OPS')")
    public ApiResponse<Void> reconcile(@PathVariable UUID runId) {
        service.reconcile(runId);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<Page<SyncRunDTO>> runs(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(service.runs(id, pageable));
    }
}
