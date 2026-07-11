package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionDiffDTO;
import com.onelake.orchestration.dto.PipelineVersionSummaryDTO;
import com.onelake.orchestration.service.PipelineVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 流水线不可变发布版本查询接口。 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines/{dagId}/versions")
@RequiredArgsConstructor
@Tag(name = "流水线版本", description = "查询不可变发布版本及其完整快照。")
public class PipelineVersionController {

    private final PipelineVersionService versionService;

    @GetMapping
    @PreAuthorize("hasRole('DE')")
    @Operation(summary = "查询发布版本历史")
    public ApiResponse<List<PipelineVersionSummaryDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(versionService.listVersions(dagId));
    }

    @GetMapping("/{version}")
    @PreAuthorize("hasRole('DE')")
    @Operation(summary = "查看指定版本完整快照")
    public ApiResponse<PipelineVersionDetailDTO> detail(@PathVariable UUID dagId,
                                                         @PathVariable Integer version) {
        return ApiResponse.ok(versionService.getVersion(dagId, version));
    }

    @GetMapping("/diff")
    @PreAuthorize("hasRole('DE')")
    @Operation(summary = "对比两个发布版本", description = "按任务、边和参数输出新增、删除及字段级修改。")
    public ApiResponse<PipelineVersionDiffDTO> diff(@PathVariable UUID dagId,
                                                     @RequestParam("from") Integer fromVersion,
                                                     @RequestParam("to") Integer toVersion) {
        return ApiResponse.ok(versionService.diff(dagId, fromVersion, toVersion));
    }

    @PostMapping("/{version}/rollback")
    @PreAuthorize("hasRole('DE')")
    @Operation(summary = "回滚指定版本到 DEV 草稿",
            description = "覆盖当前草稿并标记未发布变更，不修改历史版本；需由用户再次发布生成新版本。")
    public ApiResponse<Void> rollback(@PathVariable UUID dagId,
                                      @PathVariable Integer version) {
        versionService.rollback(dagId, version);
        return ApiResponse.ok();
    }
}
