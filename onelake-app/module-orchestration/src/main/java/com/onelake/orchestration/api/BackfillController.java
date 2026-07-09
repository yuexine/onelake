package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.CreateBackfillRequest;
import com.onelake.orchestration.service.BackfillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 业务日期区间回填 API。
 */
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;

    @PostMapping("/pipelines/{dagId}/backfill")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<BackfillDTO> create(@PathVariable UUID dagId,
                                           @Valid @RequestBody CreateBackfillRequest request) {
        return ApiResponse.ok(backfillService.createBackfill(
                dagId,
                request.rangeStart(),
                request.rangeEnd(),
                request.grain(),
                request.maxParallel()));
    }

    @GetMapping("/backfills/{id}")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<BackfillDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(backfillService.getBackfill(id));
    }

    @GetMapping("/pipelines/{dagId}/backfills")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    public ApiResponse<List<BackfillDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(backfillService.listBackfills(dagId));
    }

    @PostMapping("/backfills/{id}/cancel")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<BackfillDTO> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(backfillService.cancelBackfill(id));
    }
}
