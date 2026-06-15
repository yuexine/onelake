package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.client.AirbyteSyncDriver;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.entity.SyncRun;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.RunStatus;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.mapper.SyncTaskMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SyncRunRepository;
import com.onelake.integration.repository.SyncTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncTaskServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SyncTaskRepository taskRepo;
    private SyncRunRepository runRepo;
    private DataSourceRepository dsRepo;
    private AirbyteSyncDriver airbyte;
    private AuditLogger audit;
    private SyncTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        taskRepo = mock(SyncTaskRepository.class);
        runRepo = mock(SyncRunRepository.class);
        dsRepo = mock(DataSourceRepository.class);
        airbyte = mock(AirbyteSyncDriver.class);
        audit = mock(AuditLogger.class);
        SyncTaskMapper mapper = Mappers.getMapper(SyncTaskMapper.class);
        service = new SyncTaskServiceImpl(taskRepo, runRepo, dsRepo, mapper, airbyte, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createRequiresTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.create(createVo(UUID.randomUUID(), "orders-cdc")))
            .isInstanceOf(BizException.class)
            .hasMessage("租户上下文缺失");
    }

    @Test
    void createChecksSourceAndDuplicateNameThenPersistsDraftTask() {
        UUID sourceId = UUID.randomUUID();
        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));
        when(taskRepo.existsByTenantIdAndName(TENANT_ID, "orders-cdc")).thenReturn(false);
        when(taskRepo.save(any(SyncTask.class))).thenAnswer(invocation -> {
            SyncTask task = invocation.getArgument(0);
            task.setId(UUID.randomUUID());
            return task;
        });
        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));

        SyncTaskDTO dto = service.create(createVo(sourceId, "orders-cdc"));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.sourceId()).isEqualTo(sourceId);
        assertThat(dto.sourceName()).isEqualTo("orders-db");
        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(dto.mode()).isEqualTo("CDC");
        verify(audit).auditCreate("sync_task", dto.id(), null);
    }

    @Test
    void createRejectsMissingSourceAndDuplicateName() {
        UUID sourceId = UUID.randomUUID();
        when(dsRepo.findById(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(createVo(sourceId, "orders-cdc")))
            .isInstanceOf(BizException.class)
            .hasMessage("数据源不存在");

        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));
        when(taskRepo.existsByTenantIdAndName(TENANT_ID, "orders-cdc")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createVo(sourceId, "orders-cdc")))
            .isInstanceOf(BizException.class)
            .hasMessage("采集任务名称已存在");
    }

    @Test
    void listAppliesOptionalFiltersAndRejectsUnsupportedValues() {
        UUID sourceId = UUID.randomUUID();
        SyncTask task = task(UUID.randomUUID(), sourceId, TaskStatus.ENABLED);
        when(taskRepo.findAll(any(Specification.class))).thenReturn(List.of(task));
        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));

        List<SyncTaskDTO> result = service.list(sourceId, "cdc", "enabled", "orders");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceName()).isEqualTo("orders-db");
        verify(taskRepo).findAll(any(Specification.class));

        assertThatThrownBy(() -> service.list(null, "streaming", null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不支持的采集模式");

        assertThatThrownBy(() -> service.list(null, null, "deleted", null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不支持的任务状态");
    }

    @Test
    void updatePatchesDraftTaskAndRejectsEnabledTask() {
        UUID taskId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        SyncTask task = task(taskId, sourceId, TaskStatus.DRAFT);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));

        SyncTaskDTO dto = service.update(taskId, new UpdateSyncTaskVO(
            "orders-full",
            "full",
            "dwd.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 0 * * * ?",
            2000,
            9,
            "conn-new"
        ));

        assertThat(dto.name()).isEqualTo("orders-full");
        assertThat(dto.mode()).isEqualTo("FULL");
        assertThat(dto.targetTable()).isEqualTo("dwd.orders");
        assertThat(dto.rateLimit()).isEqualTo(2000);
        assertThat(dto.dirtyThreshold()).isEqualTo(9);
        assertThat(dto.airbyteConnectionId()).isEqualTo("conn-new");
        verify(audit).auditUpdate("sync_task", taskId, Map.of("fields", "patched"));

        SyncTask enabled = task(UUID.randomUUID(), sourceId, TaskStatus.ENABLED);
        when(taskRepo.findById(enabled.getId())).thenReturn(Optional.of(enabled));

        assertThatThrownBy(() -> service.update(enabled.getId(), new UpdateSyncTaskVO("x", null, null, null, null, null, null, null)))
            .isInstanceOf(BizException.class)
            .hasMessage("已启用任务请先暂停再编辑");
    }

    @Test
    void deleteProtectsEnabledAndRunningTasks() {
        UUID sourceId = UUID.randomUUID();
        SyncTask enabled = task(UUID.randomUUID(), sourceId, TaskStatus.ENABLED);
        when(taskRepo.findById(enabled.getId())).thenReturn(Optional.of(enabled));

        assertThatThrownBy(() -> service.delete(enabled.getId()))
            .isInstanceOf(BizException.class)
            .hasMessage("已启用任务不能删除，请先暂停");

        SyncTask draft = task(UUID.randomUUID(), sourceId, TaskStatus.DRAFT);
        when(taskRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(runRepo.existsByTaskIdAndStatus(draft.getId(), RunStatus.RUNNING)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(draft.getId()))
            .isInstanceOf(BizException.class)
            .hasMessage("任务存在运行中实例，不能删除");
        verify(taskRepo, never()).delete(draft);
    }

    @Test
    void enableDisableAndDeleteHappyPathAuditStateChanges() {
        UUID sourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SyncTask task = task(taskId, sourceId, TaskStatus.DRAFT);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(dsRepo.findById(sourceId)).thenReturn(Optional.of(datasource(sourceId, "orders-db")));

        assertThat(service.enable(taskId).status()).isEqualTo("ENABLED");
        assertThat(service.disable(taskId).status()).isEqualTo("PAUSED");

        task.setStatus(TaskStatus.DRAFT);
        when(runRepo.existsByTaskIdAndStatus(taskId, RunStatus.RUNNING)).thenReturn(false);
        service.delete(taskId);

        verify(audit).audit("ENABLE", "sync_task", taskId.toString(), null);
        verify(audit).audit("DISABLE", "sync_task", taskId.toString(), null);
        verify(audit).auditDelete("sync_task", taskId);
        verify(taskRepo).delete(task);
    }

    @Test
    void triggerRequiresEnabledTaskAndAirbyteConnection() {
        UUID sourceId = UUID.randomUUID();
        SyncTask draft = task(UUID.randomUUID(), sourceId, TaskStatus.DRAFT);
        when(taskRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.trigger(draft.getId()))
            .isInstanceOf(BizException.class)
            .hasMessage("任务未启用");

        SyncTask enabledWithoutConnection = task(UUID.randomUUID(), sourceId, TaskStatus.ENABLED);
        enabledWithoutConnection.setAirbyteConnectionId("");
        when(taskRepo.findById(enabledWithoutConnection.getId())).thenReturn(Optional.of(enabledWithoutConnection));

        assertThatThrownBy(() -> service.trigger(enabledWithoutConnection.getId()))
            .isInstanceOf(BizException.class)
            .hasMessage("任务未绑定 Airbyte Connection，暂不能触发");
    }

    @Test
    void triggerCreatesRunningRunFromAirbyteJob() {
        UUID sourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SyncTask task = task(taskId, sourceId, TaskStatus.ENABLED);
        task.setAirbyteConnectionId("conn-1");
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(airbyte.triggerSync("conn-1")).thenReturn(987L);
        when(runRepo.save(any(SyncRun.class))).thenAnswer(invocation -> {
            SyncRun run = invocation.getArgument(0);
            run.setId(UUID.randomUUID());
            return run;
        });

        UUID runId = service.trigger(taskId);

        assertThat(runId).isNotNull();
        verify(runRepo).save(any(SyncRun.class));
        verify(audit).audit(eq("TRIGGER"), eq("sync_task"), eq(taskId.toString()), any(Map.class));
    }

    @Test
    void reconcileUpdatesTerminalStatusAndIgnoresDriverFailures() {
        UUID runId = UUID.randomUUID();
        SyncRun run = new SyncRun();
        run.setId(runId);
        run.setTaskId(UUID.randomUUID());
        run.setExternalJobId("987");
        run.setStatus(RunStatus.RUNNING);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(airbyte.getJobStatus(987L)).thenReturn("succeeded");

        service.reconcile(runId);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getFinishedAt()).isNotNull();

        when(airbyte.getJobStatus(987L)).thenThrow(new RuntimeException("airbyte down"));
        service.reconcile(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void runsCalculatesDurationAndThroughput() {
        UUID taskId = UUID.randomUUID();
        SyncRun run = new SyncRun();
        run.setId(UUID.randomUUID());
        run.setTaskId(taskId);
        run.setExternalJobId("321");
        run.setStatus(RunStatus.SUCCEEDED);
        run.setRowsRead(120L);
        run.setRowsWritten(100L);
        run.setStartedAt(Instant.parse("2026-06-15T00:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-06-15T00:00:02Z"));
        when(runRepo.findByTaskIdOrderByStartedAtDesc(eq(taskId), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(run)));

        Page<SyncRunDTO> page = service.runs(taskId, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).durationMs()).isEqualTo(2000L);
        assertThat(page.getContent().get(0).throughputRows()).isEqualTo(50L);
    }

    private CreateSyncTaskVO createVo(UUID sourceId, String name) {
        return new CreateSyncTaskVO(
            sourceId,
            name,
            "cdc",
            "ods.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 */5 * * * ?",
            1000,
            5,
            "conn-1"
        );
    }

    private DataSource datasource(UUID id, String name) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setTenantId(TENANT_ID);
        ds.setName(name);
        ds.setType(DataSourceType.POSTGRES);
        ds.setConfig(JsonUtil.toJson(Map.of("host", "db.internal", "port", 5432)));
        return ds;
    }

    private SyncTask task(UUID id, UUID sourceId, TaskStatus status) {
        SyncTask task = new SyncTask();
        task.setId(id);
        task.setTenantId(TENANT_ID);
        task.setSourceId(sourceId);
        task.setName("orders-cdc");
        task.setMode(SyncMode.CDC);
        task.setTargetTable("ods.orders");
        task.setFieldMapping("[{\"source\":\"id\",\"target\":\"id\"}]");
        task.setRateLimit(1000);
        task.setDirtyThreshold(5);
        task.setAirbyteConnectionId("conn-1");
        task.setStatus(status);
        return task;
    }
}
