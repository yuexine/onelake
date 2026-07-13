package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.notification.NotificationService;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.PipelineNodeNotificationRequest;
import com.onelake.orchestration.dto.PipelineNodeNotificationResult;
import com.onelake.orchestration.dto.SubPipelineRunResult;
import com.onelake.orchestration.dto.SubPipelineTriggerRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Backend boundary used by graph-only SUB_PIPELINE and NOTIFY nodes. */
@Service
@RequiredArgsConstructor
public class PipelineNodeExecutionService {

    private static final int MAX_SUB_PIPELINE_DEPTH = 32;

    private final JobRunRepository jobRunRepository;
    private final TaskRunRepository taskRunRepository;
    private final DagRepository dagRepository;
    private final PipelineSnapshotService pipelineSnapshotService;
    private final OrchestrationService orchestrationService;
    private final NotificationService notificationService;

    /** Trigger a tenant-local published child snapshot, idempotently per parent run/task attempt. */
    @Transactional
    public SubPipelineRunResult triggerSubPipeline(SubPipelineTriggerRequest request) {
        ParentContext parent = requireParent(request.parentRunId());
        UUID subDagId = request.subDagId();
        if (parent.run().getDagId().equals(subDagId)) {
            throw new BizException(40020, "SUB_PIPELINE 禁止引用当前流水线自身");
        }
        requireCurrentTaskAttempt(parent, request);
        Dag childDag = requireTenantDag(subDagId, parent.dag().getTenantId());
        UUID childVersionId = childDag.getPublishedVersionId();
        if (childVersionId == null) {
            throw new BizException(40064, "SUB_PIPELINE 目标流水线尚未发布");
        }
        validateNoPublishedCycle(
                parent.run().getDagId(), subDagId, childVersionId, parent.dag().getTenantId());

        RunContext childContext = new RunContext(
                parent.run().getLogicalDate(),
                parent.run().getDataIntervalStart(),
                parent.run().getDataIntervalEnd(),
                parent.run().getTimezone(),
                parent.run().getRunMode(),
                parent.run().getBackfillId(),
                TriggerType.EVENT);
        String eventKey = sha256Hex(
                request.parentRunId() + ":" + request.taskKey() + ":"
                        + request.attempt() + ":" + subDagId);

        UUID previousTenant = TenantContext.getTenantId();
        UUID previousUser = TenantContext.getUserId();
        String previousUsername = TenantContext.getUsername();
        String previousTraceId = TenantContext.getTraceId();
        try {
            TenantContext.setTenantId(parent.dag().getTenantId());
            if (parent.run().getTriggeredBy() != null) {
                TenantContext.setUserId(parent.run().getTriggeredBy());
            }
            if (parent.run().getTriggeredByName() != null) {
                TenantContext.setUsername(parent.run().getTriggeredByName());
            }
            UUID childRunId = orchestrationService.triggerPipelineRun(
                    subDagId, TriggerType.EVENT, childContext, childVersionId, eventKey);
            JobRun child = jobRunRepository.findById(childRunId)
                    .orElseThrow(() -> new BizException(50000, "子流水线运行创建后无法读取"));
            return new SubPipelineRunResult(childRunId, child.getStatus());
        } finally {
            restoreTenantContext(previousTenant, previousUser, previousUsername, previousTraceId);
        }
    }

    /** Read a child status only when both runs belong to the same tenant. */
    public SubPipelineRunResult subPipelineStatus(UUID parentRunId, UUID childRunId) {
        ParentContext parent = requireParent(parentRunId);
        JobRun child = jobRunRepository.findById(childRunId)
                .orElseThrow(() -> new BizException(40400, "子流水线运行不存在"));
        requireTenantDag(child.getDagId(), parent.dag().getTenantId());
        return new SubPipelineRunResult(child.getId(), child.getStatus());
    }

    /** Persist an idempotent module-common notification for a NOTIFY node. */
    public PipelineNodeNotificationResult notify(PipelineNodeNotificationRequest request) {
        ParentContext parent = requireParent(request.parentRunId());
        UUID receiverId = request.receiverId() != null
                ? request.receiverId() : parent.run().getTriggeredBy();
        if (receiverId == null) {
            throw new BizException(40020, "NOTIFY config.receiverId 不能为空（系统触发运行无默认接收人）");
        }
        String sourceRefId = request.parentRunId() + ":"
                + sha256Hex(request.taskKey()).substring(0, 32);
        boolean created = notificationService.notifyPipelineNode(
                parent.dag().getTenantId(), receiverId, request.title(), request.message(),
                request.link(), request.level(), sourceRefId);
        return new PipelineNodeNotificationResult(created);
    }

    private void validateNoPublishedCycle(UUID parentDagId,
                                          UUID subDagId,
                                          UUID subPipelineVersionId,
                                          UUID tenantId) {
        Set<UUID> visited = new HashSet<>();
        Set<UUID> visiting = new LinkedHashSet<>();
        Deque<UUID> path = new ArrayDeque<>();
        walkPublishedSubPipelines(
                subDagId, subPipelineVersionId, parentDagId, tenantId,
                visited, visiting, path, 0);
    }

    private void walkPublishedSubPipelines(UUID dagId,
                                           UUID pinnedVersionId,
                                           UUID forbiddenParent,
                                           UUID tenantId,
                                           Set<UUID> visited,
                                           Set<UUID> visiting,
                                           Deque<UUID> path,
                                           int depth) {
        if (dagId.equals(forbiddenParent)) {
            throw new BizException(40020, "SUB_PIPELINE 引用会形成流水线调用环: " + path + " -> " + dagId);
        }
        if (depth > MAX_SUB_PIPELINE_DEPTH) {
            throw new BizException(40020, "SUB_PIPELINE 调用深度不能超过 " + MAX_SUB_PIPELINE_DEPTH);
        }
        if (visited.contains(dagId)) {
            return;
        }
        if (!visiting.add(dagId)) {
            throw new BizException(40020, "SUB_PIPELINE 发布快照中存在调用环: " + path + " -> " + dagId);
        }
        path.addLast(dagId);
        Dag dag = requireTenantDag(dagId, tenantId);
        UUID versionId = pinnedVersionId != null ? pinnedVersionId : dag.getPublishedVersionId();
        if (versionId != null) {
            for (UUID referenced : publishedSubPipelineIds(dag, versionId, tenantId)) {
                walkPublishedSubPipelines(
                        referenced, null, forbiddenParent, tenantId,
                        visited, visiting, path, depth + 1);
            }
        }
        path.removeLast();
        visiting.remove(dagId);
        visited.add(dagId);
    }

    private Set<UUID> publishedSubPipelineIds(Dag dag, UUID versionId, UUID tenantId) {
        Set<UUID> referenced = new LinkedHashSet<>();
        for (PipelineTask task : pipelineSnapshotService
                .loadExecutionSnapshotForRuntime(versionId, dag.getId(), tenantId).tasks()) {
            if (task.getTaskType() != TaskType.SUB_PIPELINE) {
                continue;
            }
            try {
                JsonNode config = JsonUtil.parse(task.getConfig());
                String value = config.path("subDagId").asText("").trim();
                if (value.isEmpty()) {
                    value = config.path("sub_dag_id").asText("").trim();
                }
                referenced.add(UUID.fromString(value));
            } catch (RuntimeException ex) {
                throw new BizException(40020,
                        "SUB_PIPELINE 发布快照配置无效: " + task.getTaskKey() + " - " + ex.getMessage());
            }
        }
        return referenced;
    }

    private ParentContext requireParent(UUID runId) {
        JobRun run = jobRunRepository.findById(runId)
                .orElseThrow(() -> new BizException(40400, "父流水线运行不存在"));
        Dag dag = dagRepository.findById(run.getDagId())
                .orElseThrow(() -> new BizException(40400, "父流水线不存在"));
        return new ParentContext(run, dag);
    }

    private TaskRun requireCurrentTaskAttempt(ParentContext parent,
                                              SubPipelineTriggerRequest request) {
        if (parent.run().getStatus() != DagStatus.RUNNING) {
            throw new BizException(40020,
                    "SUB_PIPELINE 父流水线运行已非 RUNNING，拒绝触发子流水线");
        }
        TaskRun taskRun = taskRunRepository.findByJobRunIdAndTaskKeyForUpdate(
                        request.parentRunId(), request.taskKey())
                .orElseThrow(() -> new BizException(
                        40400, "SUB_PIPELINE 父节点运行不存在: " + request.taskKey()));
        if (taskRun.getAttempt() != request.attempt()) {
            throw new BizException(40022,
                    "SUB_PIPELINE attempt 已过期，当前为 " + taskRun.getAttempt()
                            + "，请求为 " + request.attempt());
        }
        if (isTerminalTaskStatus(taskRun.getStatus())) {
            throw new BizException(40020,
                    "SUB_PIPELINE 父节点已终态，拒绝重复触发: " + taskRun.getStatus());
        }
        if (parent.dag().getTenantId() != null && taskRun.getTenantId() != null
                && !parent.dag().getTenantId().equals(taskRun.getTenantId())) {
            throw new BizException(40400,
                    "SUB_PIPELINE 父节点运行不存在: " + request.taskKey());
        }
        return taskRun;
    }

    private static boolean isTerminalTaskStatus(TaskRunStatus status) {
        return status == TaskRunStatus.SUCCEEDED
                || status == TaskRunStatus.FAILED
                || status == TaskRunStatus.CANCELLED
                || status == TaskRunStatus.UPSTREAM_FAILED
                || status == TaskRunStatus.SKIPPED;
    }

    private Dag requireTenantDag(UUID dagId, UUID tenantId) {
        return dagRepository.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "子流水线不存在或不属于当前租户"));
    }

    private static void restoreTenantContext(UUID tenantId,
                                             UUID userId,
                                             String username,
                                             String traceId) {
        TenantContext.clear();
        if (tenantId != null) TenantContext.setTenantId(tenantId);
        if (userId != null) TenantContext.setUserId(userId);
        if (username != null) TenantContext.setUsername(username);
        if (traceId != null && !"-".equals(traceId)) TenantContext.setTraceId(traceId);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record ParentContext(JobRun run, Dag dag) {
    }
}
