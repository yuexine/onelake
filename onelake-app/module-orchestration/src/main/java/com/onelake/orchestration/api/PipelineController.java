package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.OdsDwdTemplateRequest;
import com.onelake.orchestration.dto.OdsDwdTemplateResult;
import com.onelake.orchestration.dto.PipelineTaskDTO;
import com.onelake.orchestration.dto.PipelineTaskEdgeDTO;
import com.onelake.orchestration.dto.PipelineTaskEdgeRequest;
import com.onelake.orchestration.dto.PipelineTaskRequest;
import com.onelake.orchestration.dto.PipelineValidationResult;
import com.onelake.orchestration.dto.TaskRunDTO;
import com.onelake.orchestration.dto.TaskRerunRequest;
import com.onelake.orchestration.dto.TaskRerunResult;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineService;
import com.onelake.orchestration.service.RunContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流水线 V2 REST API，供统一流水线编辑器使用。
 *
 * <p>URL 约定：{@code /api/v1/orchestration/pipelines/...}
 */
@RestController
@RequestMapping("/api/v1/orchestration/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final OrchestrationService orchestrationService;

    // ---------- 流水线（dag） ----------

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Dag> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String kind = body.getOrDefault("pipelineKind", "BLANK");
        return ApiResponse.ok(pipelineService.createPipeline(name, kind));
    }

    @GetMapping("/{dagId}")
    @PreAuthorize("hasAnyRole('DE','CONSUMER','OPS')")
    public ApiResponse<Dag> get(@PathVariable UUID dagId) {
        return ApiResponse.ok(pipelineService.getPipeline(dagId));
    }

    @PutMapping("/{dagId}/status")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Dag> updateStatus(@PathVariable UUID dagId,
                                         @RequestBody Map<String, String> body) {
        return ApiResponse.ok(pipelineService.updatePipelineStatus(dagId, body.get("status")));
    }

    // ---------- 节点 ----------

    @GetMapping("/{dagId}/tasks")
    @PreAuthorize("hasAnyRole('DE','CONSUMER','OPS')")
    public ApiResponse<List<PipelineTaskDTO>> listTasks(@PathVariable UUID dagId) {
        return ApiResponse.ok(pipelineService.listTasks(dagId));
    }

    @PostMapping("/{dagId}/tasks")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineTaskDTO> createTask(@PathVariable UUID dagId,
                                                    @RequestBody PipelineTaskRequest req) {
        return ApiResponse.ok(pipelineService.createTask(dagId, req));
    }

    @PutMapping("/{dagId}/tasks/{taskKey}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineTaskDTO> updateTask(@PathVariable UUID dagId,
                                                    @PathVariable String taskKey,
                                                    @RequestBody PipelineTaskRequest req) {
        return ApiResponse.ok(pipelineService.updateTask(dagId, taskKey, req));
    }

    @DeleteMapping("/{dagId}/tasks/{taskKey}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> deleteTask(@PathVariable UUID dagId,
                                         @PathVariable String taskKey) {
        pipelineService.deleteTask(dagId, taskKey);
        return ApiResponse.ok(null);
    }

    // ---------- 边 ----------

    @GetMapping("/{dagId}/edges")
    @PreAuthorize("hasAnyRole('DE','CONSUMER','OPS')")
    public ApiResponse<List<PipelineTaskEdgeDTO>> listEdges(@PathVariable UUID dagId) {
        return ApiResponse.ok(pipelineService.listEdges(dagId));
    }

    @PostMapping("/{dagId}/edges")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineTaskEdgeDTO> createEdge(@PathVariable UUID dagId,
                                                        @RequestBody PipelineTaskEdgeRequest req) {
        return ApiResponse.ok(pipelineService.createEdge(dagId, req));
    }

    @DeleteMapping("/{dagId}/edges")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> deleteEdge(@PathVariable UUID dagId,
                                         @RequestParam String sourceKey,
                                         @RequestParam String targetKey) {
        pipelineService.deleteEdge(dagId, sourceKey, targetKey);
        return ApiResponse.ok(null);
    }

    // ---------- L1 + L2 校验 ----------

    @PostMapping("/{dagId}/validate")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<PipelineValidationResult> validate(@PathVariable UUID dagId) {
        return ApiResponse.ok(pipelineService.validate(dagId));
    }

    // ---------- 触发 ----------

    @PostMapping("/{dagId}/trigger")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, UUID>> trigger(@PathVariable UUID dagId,
                                                   @RequestParam(defaultValue = "MANUAL") String trigger,
                                                   @RequestParam(required = false) Instant logicalDate,
                                                   @RequestParam(required = false) Instant dataIntervalStart,
                                                   @RequestParam(required = false) Instant dataIntervalEnd) {
        TriggerType tt = TriggerType.valueOf(trigger);
        UUID runId = orchestrationService.triggerPipelineRun(
                dagId,
                tt,
                new RunContext(
                        logicalDate,
                        dataIntervalStart,
                        dataIntervalEnd,
                        null,
                        null,
                        null,
                        tt));
        return ApiResponse.ok(Map.of("runId", runId));
    }

    @PostMapping("/{dagId}/runs/{runId}/tasks/{taskKey}/rerun")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<TaskRerunResult> rerunTask(@PathVariable UUID dagId,
                                                  @PathVariable UUID runId,
                                                  @PathVariable String taskKey,
                                                  @RequestBody(required = false) TaskRerunRequest request) {
        String mode = request == null ? null : request.mode();
        return ApiResponse.ok(orchestrationService.rerunTask(dagId, runId, taskKey, mode));
    }

    // ---------- ODS→DWD 模板（P3） ----------

    @PostMapping("/templates/ods-dwd")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<OdsDwdTemplateResult> applyOdsDwdTemplate(@RequestBody OdsDwdTemplateRequest req) {
        return ApiResponse.ok(pipelineService.applyOdsDwdTemplate(req));
    }

    // ---------- 节点运行 ----------

    @GetMapping("/{dagId}/runs/{runId}/tasks")
    @PreAuthorize("hasAnyRole('DE','CONSUMER','OPS')")
    public ApiResponse<List<TaskRunDTO>> listTaskRuns(@PathVariable UUID dagId,
                                                       @PathVariable UUID runId) {
        return ApiResponse.ok(pipelineService.listTaskRuns(dagId, runId));
    }

    @GetMapping("/{dagId}/runs/{runId}/tasks/{taskKey}/log")
    @PreAuthorize("hasRole('DE')")
    public ResponseEntity<StreamingResponseBody> readTaskRunLog(
            @PathVariable UUID dagId,
            @PathVariable UUID runId,
            @PathVariable String taskKey,
            @RequestParam(required = false, name = "tail") Integer tailLines,
            @RequestParam(defaultValue = "false") boolean download) {
        OrchestrationService.TaskRunLogResource logResource =
                orchestrationService.readTaskRunLog(dagId, runId, taskKey, tailLines);
        StreamingResponseBody body = outputStream -> {
            try (var inputStream = logResource.content()) {
                inputStream.transferTo(outputStream);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentLength(logResource.contentLength());
        if (download) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(logResource.filename())
                    .build());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }
}
