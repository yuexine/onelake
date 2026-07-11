package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.ScheduleCalendar;
import com.onelake.orchestration.dto.DagSchedulingDTO;
import com.onelake.orchestration.dto.UpdateDagSchedulingRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.ScheduleCalendarRepository;
import com.onelake.orchestration.repository.PipelineDependencyWaitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流水线调度配置更新、租户边界与字段校验测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineSchedulingServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private DagRepository dagRepo;
    @Mock private ScheduleCalendarRepository calendarRepo;
    @Mock private PipelineDependencyWaitRepository scheduleWaitRepo;

    private PipelineSchedulingService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new PipelineSchedulingService(dagRepo, calendarRepo, scheduleWaitRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void updatesCompleteSchedulingPolicy() {
        Dag dag = dag();
        Dag persisted = dag();
        persisted.setId(dag.getId());
        UUID calendarId = UUID.randomUUID();
        ScheduleCalendar calendar = new ScheduleCalendar();
        calendar.setId(calendarId);
        calendar.setTenantId(TENANT_ID);
        when(dagRepo.findByIdAndTenantId(dag.getId(), TENANT_ID))
                .thenReturn(Optional.of(dag), Optional.of(persisted));
        when(calendarRepo.findByIdAndTenantId(calendarId, TENANT_ID))
                .thenReturn(Optional.of(calendar));
        when(dagRepo.updateSchedulingPolicy(
                any(UUID.class), any(UUID.class), anyString(), anyBoolean(),
                anyInt(), anyInt(), anyString(), anyString(), anyInt(), any(), any(),
                anyInt(), anyInt(), any(UUID.class),
                any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Instant start = Instant.parse("2026-07-10T00:00:00Z");
        Instant end = Instant.parse("2026-08-10T00:00:00Z");
        persisted.setTimezone("Asia/Shanghai");
        persisted.setCatchup(true);
        persisted.setMaxActiveRuns(2);
        persisted.setPriority(9);
        persisted.setScheduleMode("DRY_RUN");
        persisted.setMisfirePolicy("SKIP");
        persisted.setDependencyWaitTimeoutMinutes(180);
        persisted.setSlaMinutes(60);
        persisted.setTimeoutMinutes(120);
        persisted.setRunRetryCount(3);
        persisted.setRunRetryIntervalSeconds(30);
        persisted.setCalendarId(calendarId);
        persisted.setScheduleStart(start);
        persisted.setScheduleEnd(end);
        DagSchedulingDTO result = service.updateScheduling(
                dag.getId(),
                new UpdateDagSchedulingRequest(
                        "Asia/Shanghai", true, 2, 9, "dry_run",
                        "skip", 180, 60, 120, 3, 30, calendarId, start, end));

        assertThat(result.scheduleMode()).isEqualTo("DRY_RUN");
        assertThat(result.catchup()).isTrue();
        assertThat(result.maxActiveRuns()).isEqualTo(2);
        assertThat(result.priority()).isEqualTo(9);
        assertThat(result.misfirePolicy()).isEqualTo("SKIP");
        assertThat(result.dependencyWaitTimeoutMinutes()).isEqualTo(180);
        assertThat(result.runRetryCount()).isEqualTo(3);
        assertThat(result.runRetryIntervalSeconds()).isEqualTo(30);
        assertThat(result.calendarId()).isEqualTo(calendarId);
        assertThat(result.scheduleStart()).isEqualTo(start);
        assertThat(result.scheduleEnd()).isEqualTo(end);
        verify(dagRepo).updateSchedulingPolicy(
                dag.getId(), TENANT_ID, "Asia/Shanghai", true, 2, 9,
                "DRY_RUN", "SKIP", 180, 60, 120, 3, 30, calendarId, start, end);
        verify(dagRepo, never()).save(any(Dag.class));
    }

    @Test
    void rejectsInvalidTimezoneAndReversedWindow() {
        Dag dag = dag();
        when(dagRepo.findByIdAndTenantId(dag.getId(), TENANT_ID))
                .thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updateScheduling(
                dag.getId(),
                new UpdateDagSchedulingRequest(
                        "Mars/Olympus", false, 1, 5, "NORMAL",
                        "FIRE_ONCE", 1440, null, null, 0, 0, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("timezone");

        assertThatThrownBy(() -> service.updateScheduling(
                dag.getId(),
                new UpdateDagSchedulingRequest(
                        "UTC", false, 1, 5, "NORMAL",
                        "FIRE_ONCE", 1440, null, null, 0, 0, null,
                        Instant.parse("2026-08-10T00:00:00Z"),
                        Instant.parse("2026-07-10T00:00:00Z"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("scheduleEnd");

        assertThatThrownBy(() -> service.updateScheduling(
                dag.getId(),
                new UpdateDagSchedulingRequest(
                        "UTC", false, 1, 5, "NORMAL",
                        "FIRE_ONCE", 1440, null, null, -1, 0, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("runRetryCount");
    }

    @Test
    void preservesExistingRetryPolicyWhenLegacyClientOmitsNewFields() {
        Dag dag = dag();
        dag.setRunRetryCount(2);
        dag.setRunRetryIntervalSeconds(45);
        Dag persisted = dag();
        persisted.setId(dag.getId());
        persisted.setRunRetryCount(2);
        persisted.setRunRetryIntervalSeconds(45);
        when(dagRepo.findByIdAndTenantId(dag.getId(), TENANT_ID))
                .thenReturn(Optional.of(dag), Optional.of(persisted));
        when(dagRepo.updateSchedulingPolicy(
                any(UUID.class), any(UUID.class), anyString(), anyBoolean(),
                anyInt(), anyInt(), anyString(), anyString(), anyInt(), any(), any(), anyInt(), anyInt(),
                any(), any(), any()))
                .thenReturn(1);

        DagSchedulingDTO result = service.updateScheduling(
                dag.getId(),
                new UpdateDagSchedulingRequest(
                        "UTC", false, 1, 5, "NORMAL",
                        null, null, null, null, null, null, null, null, null));

        assertThat(result.runRetryCount()).isEqualTo(2);
        assertThat(result.runRetryIntervalSeconds()).isEqualTo(45);
        verify(dagRepo).updateSchedulingPolicy(
                dag.getId(), TENANT_ID, "UTC", false, 1, 5,
                "NORMAL", "FIRE_ONCE", 1440, null, null, 2, 45, null, null, null);
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(TENANT_ID);
        return dag;
    }
}
