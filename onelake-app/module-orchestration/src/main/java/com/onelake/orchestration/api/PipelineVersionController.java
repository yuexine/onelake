package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionSummaryDTO;
import com.onelake.orchestration.service.PipelineSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 流水线不可变发布版本查询接口。 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines/{dagId}/versions")
@RequiredArgsConstructor
@Tag(name = "流水线版本", description = "查询不可变发布版本及其完整快照。")
public class PipelineVersionController {

    private final PipelineSnapshotService snapshotService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DE','OPS')")
    @Operation(summary = "查询发布版本历史")
    public ApiResponse<List<PipelineVersionSummaryDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(snapshotService.listVersions(dagId));
    }

    @GetMapping("/{version}")
    @PreAuthorize("hasAnyRole('DE','OPS')")
    @Operation(summary = "查看指定版本完整快照")
    public ApiResponse<PipelineVersionDetailDTO> detail(@PathVariable UUID dagId,
                                                         @PathVariable Integer version) {
        return ApiResponse.ok(snapshotService.getVersion(dagId, version));
    }
}
