package com.onelake.common.notification;

import com.onelake.common.context.TenantContext;
import com.onelake.common.task.RunningTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repo;

    @InjectMocks
    private NotificationService service;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void notifyTaskIfNeededCreatesFailedTaskNotificationOnce() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TenantContext.setUserId(userId);
        RunningTask task = failedTask(tenantId, null);
        when(repo.findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
            tenantId,
            userId,
            "sync_run",
            "run-1"
        )).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.notifyTaskIfNeeded(task);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getTenantId()).isEqualTo(tenantId);
        assertThat(notification.getReceiverId()).isEqualTo(userId);
        assertThat(notification.getCategory()).isEqualTo("TASK");
        assertThat(notification.getTitle()).isEqualTo("任务失败：采集任务 orders_sync -> ods.orders");
        assertThat(notification.getContent()).isEqualTo("账号密码过期");
        assertThat(notification.getLevel()).isEqualTo("CRITICAL");
        assertThat(notification.getLink()).isEqualTo("/integration/sync-tasks/task-1/runs/run-1");
        assertThat(notification.getSourceRefType()).isEqualTo("sync_run");
        assertThat(notification.getSourceRefId()).isEqualTo("run-1");
        assertThat(notification.getIsRead()).isFalse();
    }

    @Test
    void notifyTaskIfNeededSkipsExistingSourceNotification() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RunningTask task = failedTask(tenantId, userId);
        when(repo.findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
            tenantId,
            userId,
            "sync_run",
            "run-1"
        )).thenReturn(Optional.of(new Notification()));

        service.notifyTaskIfNeeded(task);

        verify(repo, never()).save(any(Notification.class));
    }

    @Test
    void notifyTaskIfNeededToleratesConcurrentDuplicateInsert() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RunningTask task = failedTask(tenantId, userId);
        when(repo.findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
            tenantId,
            userId,
            "sync_run",
            "run-1"
        )).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate"))
            .when(repo).save(any(Notification.class));

        assertThatNoException().isThrownBy(() -> service.notifyTaskIfNeeded(task));

        verify(repo).save(any(Notification.class));
    }

    @Test
    void notifyTaskIfNeededIgnoresNonFailedTask() {
        RunningTask task = failedTask(UUID.randomUUID(), UUID.randomUUID());
        task.setStatus("SUCCEEDED");

        service.notifyTaskIfNeeded(task);

        verify(repo, never()).save(any(Notification.class));
    }

    @Test
    void notifyPipelineNodePersistsRenderedMessageAndIsIdempotent() {
        UUID tenantId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        when(repo.insertPipelineNodeNotification(
                any(UUID.class), eq(tenantId), eq(receiverId), eq("Daily 2026-07-13"),
                eq("rows=42"), eq("/orchestration/runs/1"), eq("WARNING"),
                eq("run-node-key"), any())).thenReturn(1, 0);

        boolean created = service.notifyPipelineNode(
                tenantId, receiverId, "Daily 2026-07-13", "rows=42",
                "/orchestration/runs/1", "warning", "run-node-key");
        boolean duplicate = service.notifyPipelineNode(
                tenantId, receiverId, "Daily 2026-07-13", "rows=42",
                "/orchestration/runs/1", "warning", "run-node-key");

        assertThat(created).isTrue();
        assertThat(duplicate).isFalse();
        verify(repo, times(2)).insertPipelineNodeNotification(
                any(UUID.class), eq(tenantId), eq(receiverId), eq("Daily 2026-07-13"),
                eq("rows=42"), eq("/orchestration/runs/1"), eq("WARNING"),
                eq("run-node-key"), any());
    }

    private RunningTask failedTask(UUID tenantId, UUID userId) {
        RunningTask task = new RunningTask();
        task.setTenantId(tenantId);
        task.setUserId(userId);
        task.setStatus("FAILED");
        task.setRefType("sync_run");
        task.setRefId("run-1");
        task.setTitle("采集任务 orders_sync -> ods.orders");
        task.setDetail("账号密码过期");
        task.setErrorMessage("账号密码过期");
        task.setLink("/integration/sync-tasks/task-1/runs/run-1");
        return task;
    }
}
