package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.notification.NotificationService;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.PipelineNodeNotificationRequest;
import com.onelake.orchestration.dto.SubPipelineRunResult;
import com.onelake.orchestration.dto.SubPipelineTriggerRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineNodeExecutionServiceTest {

    @Mock private JobRunRepository jobRunRepository;
    @Mock private TaskRunRepository taskRunRepository;
    @Mock private DagRepository dagRepository;
    @Mock private PipelineSnapshotService pipelineSnapshotService;
    @Mock private OrchestrationService orchestrationService;
    @Mock private NotificationService notificationService;

    private PipelineNodeExecutionService service;
    private UUID tenantId;
    private UUID parentDagId;
    private UUID childDagId;
    private UUID parentRunId;
    private JobRun parentRun;
    private Dag parentDag;
    private Dag childDag;
    private TaskRun parentTaskRun;

    @BeforeEach
    void setUp() {
        service = new PipelineNodeExecutionService(
                jobRunRepository, taskRunRepository, dagRepository, pipelineSnapshotService,
                orchestrationService, notificationService);
        tenantId = UUID.randomUUID();
        parentDagId = UUID.randomUUID();
        childDagId = UUID.randomUUID();
        parentRunId = UUID.randomUUID();
        parentRun = run(parentRunId, parentDagId, DagStatus.RUNNING);
        parentRun.setLogicalDate(Instant.parse("2026-07-12T16:00:00Z"));
        parentRun.setDataIntervalStart(Instant.parse("2026-07-12T16:00:00Z"));
        parentRun.setDataIntervalEnd(Instant.parse("2026-07-13T16:00:00Z"));
        parentRun.setTimezone("Asia/Shanghai");
        parentRun.setRunMode("NORMAL");
        parentRun.setTriggeredBy(UUID.randomUUID());
        parentDag = dag(parentDagId, tenantId, UUID.randomUUID());
        childDag = dag(childDagId, tenantId, UUID.randomUUID());
        parentTaskRun = taskRun(parentRunId, tenantId, "call_child", 1, TaskRunStatus.RUNNING);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void triggersPublishedChildPipelineWithInheritedBusinessTime() {
        UUID childRunId = UUID.randomUUID();
        JobRun childRun = run(childRunId, childDagId, DagStatus.QUEUED);
        mockParentAndChild();
        when(pipelineSnapshotService.loadExecutionSnapshotForRuntime(
                childDag.getPublishedVersionId(), childDagId, tenantId))
                .thenReturn(snapshot(childDag, List.of()));
        when(orchestrationService.triggerPipelineRun(
                eq(childDagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(childDag.getPublishedVersionId()), any(String.class))).thenReturn(childRunId);
        when(jobRunRepository.findById(childRunId)).thenReturn(Optional.of(childRun));

        SubPipelineRunResult result = service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1));

        assertThat(result.runId()).isEqualTo(childRunId);
        assertThat(result.status()).isEqualTo(DagStatus.QUEUED);
        ArgumentCaptor<RunContext> context = ArgumentCaptor.forClass(RunContext.class);
        verify(orchestrationService).triggerPipelineRun(
                eq(childDagId), eq(TriggerType.EVENT), context.capture(),
                eq(childDag.getPublishedVersionId()), any(String.class));
        assertThat(context.getValue().logicalDate()).isEqualTo(parentRun.getLogicalDate());
        assertThat(context.getValue().dataIntervalEnd()).isEqualTo(parentRun.getDataIntervalEnd());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void usesAttemptScopedIdempotencyKeysForTaskReruns() {
        UUID childRunId = UUID.randomUUID();
        JobRun childRun = run(childRunId, childDagId, DagStatus.QUEUED);
        mockParentAndChild();
        when(pipelineSnapshotService.loadExecutionSnapshotForRuntime(
                childDag.getPublishedVersionId(), childDagId, tenantId))
                .thenReturn(snapshot(childDag, List.of()));
        when(orchestrationService.triggerPipelineRun(
                eq(childDagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(childDag.getPublishedVersionId()), any(String.class))).thenReturn(childRunId);
        when(jobRunRepository.findById(childRunId)).thenReturn(Optional.of(childRun));

        service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1));
        service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1));
        parentTaskRun.setAttempt(2);
        service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 2));

        ArgumentCaptor<String> eventKeys = ArgumentCaptor.forClass(String.class);
        verify(orchestrationService, times(3)).triggerPipelineRun(
                eq(childDagId), eq(TriggerType.EVENT), any(RunContext.class),
                eq(childDag.getPublishedVersionId()), eventKeys.capture());
        assertThat(eventKeys.getAllValues()).hasSize(3);
        assertThat(eventKeys.getAllValues().get(0))
                .isEqualTo(eventKeys.getAllValues().get(1))
                .isNotEqualTo(eventKeys.getAllValues().get(2));
    }

    @Test
    void rejectsStaleTaskAttemptBeforeTriggeringChild() {
        mockParentAndTask();
        parentTaskRun.setAttempt(2);

        assertThatThrownBy(() -> service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("attempt 已过期");

        verify(orchestrationService, never()).triggerPipelineRun(
                any(UUID.class), any(TriggerType.class), any(RunContext.class),
                any(UUID.class), any(String.class));
    }

    @Test
    void rejectsTerminalParentRunBeforeTriggeringChild() {
        when(jobRunRepository.findById(parentRunId)).thenReturn(Optional.of(parentRun));
        when(dagRepository.findById(parentDagId)).thenReturn(Optional.of(parentDag));
        parentRun.setStatus(DagStatus.CANCELLED);

        assertThatThrownBy(() -> service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已非 RUNNING");

        verify(orchestrationService, never()).triggerPipelineRun(
                any(UUID.class), any(TriggerType.class), any(RunContext.class),
                any(UUID.class), any(String.class));
    }

    @Test
    void rejectsPublishedSubPipelineCycleBeforeTriggering() {
        mockParentAndChild();
        PipelineTask backReference = new PipelineTask();
        backReference.setTaskKey("back_to_parent");
        backReference.setTaskType(TaskType.SUB_PIPELINE);
        backReference.setConfig("{\"subDagId\":\"" + parentDagId
                + "\",\"waitForCompletion\":false}");
        when(pipelineSnapshotService.loadExecutionSnapshotForRuntime(
                childDag.getPublishedVersionId(), childDagId, tenantId))
                .thenReturn(snapshot(childDag, List.of(backReference)));

        assertThatThrownBy(() -> service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "call_child", childDagId, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("调用环");

        verify(orchestrationService, never()).triggerPipelineRun(
                any(UUID.class), any(TriggerType.class), any(RunContext.class),
                any(UUID.class), any(String.class));
    }

    @Test
    void rejectsDirectSelfReference() {
        when(jobRunRepository.findById(parentRunId)).thenReturn(Optional.of(parentRun));
        when(dagRepository.findById(parentDagId)).thenReturn(Optional.of(parentDag));

        assertThatThrownBy(() -> service.triggerSubPipeline(
                new SubPipelineTriggerRequest(parentRunId, "self", parentDagId, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("自身");
    }

    @Test
    void emitsModuleCommonNotificationUsingParentActorAsDefaultReceiver() {
        when(jobRunRepository.findById(parentRunId)).thenReturn(Optional.of(parentRun));
        when(dagRepository.findById(parentDagId)).thenReturn(Optional.of(parentDag));
        when(notificationService.notifyPipelineNode(
                eq(tenantId), eq(parentRun.getTriggeredBy()), eq("Daily 2026-07-13"),
                eq("rows=42"), eq("/runs/parent"), eq("WARNING"), any(String.class)))
                .thenReturn(true);

        var result = service.notify(new PipelineNodeNotificationRequest(
                parentRunId, "notify_owner", null, "Daily 2026-07-13",
                "rows=42", "/runs/parent", "WARNING"));

        assertThat(result.created()).isTrue();
        verify(notificationService).notifyPipelineNode(
                eq(tenantId), eq(parentRun.getTriggeredBy()), eq("Daily 2026-07-13"),
                eq("rows=42"), eq("/runs/parent"), eq("WARNING"), any(String.class));
    }

    private void mockParentAndChild() {
        mockParentAndTask();
        when(dagRepository.findByIdAndTenantId(childDagId, tenantId))
                .thenReturn(Optional.of(childDag));
    }

    private void mockParentAndTask() {
        when(jobRunRepository.findById(parentRunId)).thenReturn(Optional.of(parentRun));
        when(dagRepository.findById(parentDagId)).thenReturn(Optional.of(parentDag));
        when(taskRunRepository.findByJobRunIdAndTaskKeyForUpdate(parentRunId, "call_child"))
                .thenReturn(Optional.of(parentTaskRun));
    }

    private PipelineSnapshotService.ExecutionSnapshot snapshot(Dag dag, List<PipelineTask> tasks) {
        PipelineVersion version = new PipelineVersion();
        version.setId(dag.getPublishedVersionId());
        version.setDagId(dag.getId());
        return new PipelineSnapshotService.ExecutionSnapshot(
                version, dag, tasks, List.of(), List.of());
    }

    private static Dag dag(UUID id, UUID tenantId, UUID publishedVersionId) {
        Dag dag = new Dag();
        dag.setId(id);
        dag.setTenantId(tenantId);
        dag.setPublishedVersionId(publishedVersionId);
        return dag;
    }

    private static JobRun run(UUID id, UUID dagId, DagStatus status) {
        JobRun run = new JobRun();
        run.setId(id);
        run.setDagId(dagId);
        run.setStatus(status);
        return run;
    }

    private static TaskRun taskRun(UUID jobRunId,
                                   UUID tenantId,
                                   String taskKey,
                                   int attempt,
                                   TaskRunStatus status) {
        TaskRun taskRun = new TaskRun();
        taskRun.setId(UUID.randomUUID());
        taskRun.setJobRunId(jobRunId);
        taskRun.setTenantId(tenantId);
        taskRun.setTaskKey(taskKey);
        taskRun.setAttempt(attempt);
        taskRun.setStatus(status);
        return taskRun;
    }
}
