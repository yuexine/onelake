package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.security.InternalApiTokenFilter;
import com.onelake.orchestration.dto.TaskRunCallbackRequest;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.service.OrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部节点状态回调接口。
 *
 * <p>该接口仅供 Dagster/Spark 执行器通过内部令牌调用，不面向前端用户。
 */
@RestController
@RequestMapping("/api/v1/internal/orchestration")
@RequiredArgsConstructor
public class InternalTaskRunCallbackController {

    public static final String INTERNAL_TOKEN_HEADER = InternalApiTokenFilter.INTERNAL_TOKEN_HEADER;

    private final OrchestrationService orchestrationService;
    private final DagRepository dagRepository;
    private final PipelineTaskRepository pipelineTaskRepository;
    private final PipelineTaskEdgeRepository pipelineTaskEdgeRepository;

    /**
     * 接收 Dagster 节点执行器的状态回调。
     *
     * <p>鉴权由 {@link InternalApiTokenFilter} 在进入 Controller 前完成，这里只处理
     * 已认证内部请求的参数校验与业务分发。
     */
    @PostMapping("/runs/{runId}/tasks/{taskKey}/status")
    public ApiResponse<TaskRunCallbackResult> applyTaskRunStatus(
            @PathVariable UUID runId,
            @PathVariable String taskKey,
            @Valid @RequestBody TaskRunCallbackRequest request) {
        return ApiResponse.ok(orchestrationService.applyTaskRunCallback(runId, taskKey, request));
    }

    /**
     * 供 Dagster code location 在 reload 时读取拓扑并生成真实的 per-task op 图。
     * 仅暴露 task key 与 PIPELINE 边，运行参数仍由 Java launch runConfig 传入。
     */
    @org.springframework.web.bind.annotation.GetMapping("/dagster/graph-definitions")
    public ApiResponse<List<Map<String, Object>>> graphDefinitions() {
        List<Map<String, Object>> definitions = dagRepository.findAll().stream()
                .map(dag -> {
                    List<String> taskKeys = pipelineTaskRepository.findByDagIdOrderByCreatedAtAsc(dag.getId()).stream()
                            .map(PipelineTask::getTaskKey).toList();
                    List<Map<String, String>> edges = pipelineTaskEdgeRepository.findByDagId(dag.getId()).stream()
                            .filter(edge -> edge.getEdgeLayer() == EdgeLayer.PIPELINE)
                            .filter(edge -> taskKeys.contains(edge.getSourceKey()) && taskKeys.contains(edge.getTargetKey()))
                            .map(edge -> Map.of("source_key", edge.getSourceKey(), "target_key", edge.getTargetKey()))
                            .toList();
                    return Map.<String, Object>of("pipeline_id", dag.getId().toString(),
                            "task_keys", taskKeys, "edges", edges);
                }).toList();
        return ApiResponse.ok(definitions);
    }
}
