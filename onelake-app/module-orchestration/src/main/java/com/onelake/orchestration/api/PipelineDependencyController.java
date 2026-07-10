package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.PipelineDependencyDTO;
import com.onelake.orchestration.dto.PipelineDependencyRequest;
import com.onelake.orchestration.service.PipelineDependencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 跨流水线/跨周期依赖管理接口。 */
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
public class PipelineDependencyController {

    private final PipelineDependencyService dependencyService;

    /** 列出下游流水线的依赖配置。 */
    @GetMapping("/pipelines/{dagId}/dependencies")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<PipelineDependencyDTO>> list(@PathVariable UUID dagId) {
        return ApiResponse.ok(dependencyService.listDependencies(dagId));
    }

    /** 新增依赖，并拒绝自依赖、重复依赖和传递性成环。 */
    @PostMapping("/pipelines/{dagId}/dependencies")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineDependencyDTO> create(@PathVariable UUID dagId,
                                                      @RequestBody PipelineDependencyRequest request) {
        return ApiResponse.ok(dependencyService.createDependency(dagId, request));
    }

    /** 删除指定下游的一条依赖。 */
    @DeleteMapping("/pipelines/{dagId}/dependencies/{dependencyId}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID dagId,
                                    @PathVariable UUID dependencyId) {
        dependencyService.deleteDependency(dagId, dependencyId);
        return ApiResponse.ok(null);
    }

    /** 批量列出当前租户的全部启用依赖边，避免编辑器按 DAG 扇出查询。 */
    @GetMapping("/pipeline-dependencies")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<List<PipelineDependencyDTO>> listEnabled() {
        return ApiResponse.ok(dependencyService.listEnabledDependencies());
    }
}
