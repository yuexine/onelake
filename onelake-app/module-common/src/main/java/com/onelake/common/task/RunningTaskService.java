package com.onelake.common.task;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunningTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final RunningTaskRepository repo;
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notificationService;

    @Transactional
    public List<RunningTaskDTO> listRunning(boolean includeRecent, int requestedLimit) {
        UUID tenantId = requireTenant();
        int limit = normalizeLimit(requestedLimit);
        int syncLimit = Math.max(limit, DEFAULT_LIMIT);
        syncIntegrationRuns(tenantId, syncLimit);
        syncSqlQueries(tenantId, syncLimit);
        syncOrchestrationRuns(tenantId, syncLimit);
        syncQualityResults(tenantId, syncLimit);
        Instant recentAfter = Instant.now().minus(10, ChronoUnit.MINUTES);
        return repo.findVisible(
                tenantId,
                ACTIVE_STATUSES,
                includeRecent,
                recentAfter,
                PageRequest.of(0, limit)
            )
            .stream()
            .map(this::toDTO)
            .toList();
    }

    @Transactional
    public RunningTaskDTO dismiss(UUID id) {
        UUID tenantId = requireTenant();
        RunningTask task = repo.findByTenantIdAndId(tenantId, id)
            .orElseThrow(() -> new BizException(40400, "全局任务不存在"));
        task.setDismissedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return toDTO(repo.save(task));
    }

    void syncIntegrationRuns(UUID tenantId, int limit) {
        String sql = """
            select
              sr.id::text as run_id,
              sr.task_id::text as task_id,
              st.name as task_name,
              st.target_table,
              st.tenant_id,
              sr.status,
              sr.rows_read,
              sr.rows_written,
              sr.error_code,
              sr.error_msg,
              sr.started_at,
              sr.finished_at
            from integration.sync_run sr
            join integration.sync_task st on st.id = sr.task_id
            where st.tenant_id = :tenantId
              and (
                sr.status in ('QUEUED', 'RUNNING')
                or sr.finished_at >= now() - interval '10 minutes'
                or sr.status = 'FAILED'
              )
            order by coalesce(sr.finished_at, sr.started_at) desc
            limit :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", Math.min(Math.max(limit, DEFAULT_LIMIT), MAX_LIMIT));
        try {
            jdbc.query(sql, params, integrationRunMapper()).forEach(this::upsertIntegrationRun);
        } catch (DataAccessException e) {
            log.debug("sync integration runs for global task bar skipped: {}", e.getMessage());
        }
    }

    void syncSqlQueries(UUID tenantId, int limit) {
        String sql = """
            select
              id::text as query_id,
              tenant_id,
              user_id,
              runner,
              sql_text,
              engine,
              resource_group,
              status,
              duration_ms,
              scan_bytes,
              row_count,
              error_code,
              error_message,
              created_at
            from catalog.sql_query_history
            where tenant_id = :tenantId
              and (
                status = 'RUNNING'
                or created_at >= now() - interval '10 minutes'
              )
            order by created_at desc
            limit :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", queryLimit(limit));
        try {
            jdbc.query(sql, params, sqlQueryMapper()).forEach(this::upsertSqlQuery);
        } catch (DataAccessException e) {
            log.debug("sync sql queries for global task bar skipped: {}", e.getMessage());
        }
    }

    void upsertSqlQuery(SqlQueryProjection row) {
        UUID tenantId = row.tenantId();
        RunningTask task = repo.findByTenantIdAndRefTypeAndRefId(tenantId, "sql_query", row.queryId())
            .orElseGet(RunningTask::new);
        String status = normalizeStatus(row.status());
        task.setTenantId(tenantId);
        task.setUserId(row.userId());
        task.setSourceModule("LAKEHOUSE");
        task.setTaskType("SQL");
        task.setRefType("sql_query");
        task.setRefId(row.queryId());
        task.setParentRefId(null);
        task.setTitle(sqlTitle(row));
        task.setStatus(status);
        task.setProgress(progressOf(status));
        task.setPhase(sqlPhaseOf(status));
        task.setDetail(sqlDetailOf(row, status));
        task.setErrorCode(blankToNull(row.errorCode()));
        task.setErrorMessage("FAILED".equals(status) ? blankToNull(row.errorMessage()) : null);
        task.setLink("/lakehouse/sql");
        task.setCancellable("RUNNING".equals(status));
        task.setCancelEndpoint(task.getCancellable()
            ? "/lakehouse/sql/queries/" + row.queryId() + "/cancel"
            : null);
        task.setStartedAt(row.createdAt() == null ? Instant.now() : row.createdAt());
        task.setFinishedAt(finishedAtOf(status, row.createdAt(), row.durationMs()));
        task.setExpiresAt(expiryOf(status, task.getFinishedAt()));
        task.setUpdatedAt(Instant.now());
        saveAndNotify(task);
    }

    void syncOrchestrationRuns(UUID tenantId, int limit) {
        String sql = """
            select
              jr.id::text as run_id,
              jr.dag_id::text as dag_id,
              d.tenant_id,
              d.name as dag_name,
              d.dagster_job,
              jr.dagster_run_id,
              jr.trigger_type,
              jr.status,
              jr.started_at,
              jr.finished_at
            from orchestration.job_run jr
            join orchestration.dag d on d.id = jr.dag_id
            where d.tenant_id = :tenantId
              and (
                jr.status in ('QUEUED', 'RUNNING')
                or jr.finished_at >= now() - interval '10 minutes'
              )
            order by coalesce(jr.finished_at, jr.started_at) desc
            limit :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", queryLimit(limit));
        try {
            jdbc.query(sql, params, orchestrationRunMapper()).forEach(this::upsertOrchestrationRun);
        } catch (DataAccessException e) {
            log.debug("sync orchestration runs for global task bar skipped: {}", e.getMessage());
        }
    }

    void upsertOrchestrationRun(OrchestrationRunProjection row) {
        UUID tenantId = row.tenantId();
        RunningTask task = repo.findByTenantIdAndRefTypeAndRefId(tenantId, "job_run", row.runId())
            .orElseGet(RunningTask::new);
        String status = normalizeStatus(row.status());
        task.setTenantId(tenantId);
        task.setSourceModule("ORCHESTRATION");
        task.setTaskType("DAG");
        task.setRefType("job_run");
        task.setRefId(row.runId());
        task.setParentRefId(row.dagId());
        task.setTitle(orchestrationTitle(row));
        task.setStatus(status);
        task.setProgress(progressOf(status));
        task.setPhase(orchestrationPhaseOf(status));
        task.setDetail(orchestrationDetailOf(row, status));
        task.setErrorCode("FAILED".equals(status) ? "DAG_RUN_FAILED" : null);
        task.setErrorMessage("FAILED".equals(status) ? orchestrationDetailOf(row, status) : null);
        task.setLink("/orchestration/pipelines/" + row.dagId());
        task.setCancellable(false);
        task.setCancelEndpoint(null);
        task.setStartedAt(row.startedAt() == null ? Instant.now() : row.startedAt());
        task.setFinishedAt(row.finishedAt());
        task.setExpiresAt(expiryOf(status, row.finishedAt()));
        task.setUpdatedAt(Instant.now());
        saveAndNotify(task);
    }

    void syncQualityResults(UUID tenantId, int limit) {
        String sql = """
            select
              rr.id::text as result_id,
              rr.rule_id::text as rule_id,
              qr.tenant_id,
              qr.target_fqn,
              qr.target_column,
              qr.rule_type,
              qr.severity,
              rr.passed,
              rr.pass_rate,
              rr.failed_rows,
              rr.checked_at
            from quality.run_result rr
            join quality.rule qr on qr.id = rr.rule_id
            where qr.tenant_id = :tenantId
              and rr.checked_at >= now() - interval '10 minutes'
            order by rr.checked_at desc
            limit :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", queryLimit(limit));
        try {
            jdbc.query(sql, params, qualityResultMapper()).forEach(this::upsertQualityResult);
        } catch (DataAccessException e) {
            log.debug("sync quality results for global task bar skipped: {}", e.getMessage());
        }
    }

    void upsertQualityResult(QualityResultProjection row) {
        UUID tenantId = row.tenantId();
        RunningTask task = repo.findByTenantIdAndRefTypeAndRefId(tenantId, "quality_run_result", row.resultId())
            .orElseGet(RunningTask::new);
        String status = Boolean.TRUE.equals(row.passed()) ? "SUCCEEDED" : "FAILED";
        String detail = qualityDetailOf(row);
        task.setTenantId(tenantId);
        task.setSourceModule("QUALITY");
        task.setTaskType("QUALITY");
        task.setRefType("quality_run_result");
        task.setRefId(row.resultId());
        task.setParentRefId(row.ruleId());
        task.setTitle(qualityTitle(row));
        task.setStatus(status);
        task.setProgress(100);
        task.setPhase("SUCCEEDED".equals(status) ? "稽核通过" : "稽核失败");
        task.setDetail(detail);
        task.setErrorCode("FAILED".equals(status) ? "QUALITY_CHECK_FAILED" : null);
        task.setErrorMessage("FAILED".equals(status) ? detail : null);
        task.setLink("/quality/results");
        task.setCancellable(false);
        task.setCancelEndpoint(null);
        task.setStartedAt(row.checkedAt() == null ? Instant.now() : row.checkedAt());
        task.setFinishedAt(row.checkedAt() == null ? Instant.now() : row.checkedAt());
        task.setExpiresAt(expiryOf(status, task.getFinishedAt()));
        task.setUpdatedAt(Instant.now());
        saveAndNotify(task);
    }

    void upsertIntegrationRun(IntegrationRunProjection row) {
        UUID tenantId = row.tenantId();
        RunningTask task = repo.findByTenantIdAndRefTypeAndRefId(tenantId, "sync_run", row.runId())
            .orElseGet(RunningTask::new);
        task.setTenantId(tenantId);
        task.setSourceModule("INTEGRATION");
        task.setTaskType("COLLECT");
        task.setRefType("sync_run");
        task.setRefId(row.runId());
        task.setParentRefId(row.taskId());
        task.setTitle(collectTitle(row));
        task.setStatus(normalizeStatus(row.status()));
        task.setProgress(progressOf(task.getStatus()));
        task.setPhase(phaseOf(task.getStatus()));
        task.setDetail(detailOf(row, task.getStatus()));
        task.setErrorCode(blankToNull(row.errorCode()));
        task.setErrorMessage(blankToNull(row.errorMessage()));
        task.setLink("/integration/sync-tasks/" + row.taskId() + "/runs/" + row.runId());
        task.setCancellable("QUEUED".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()));
        task.setCancelEndpoint(task.getCancellable()
            ? "/integration/sync-tasks/runs/" + row.runId() + "/cancel"
            : null);
        task.setStartedAt(row.startedAt() == null ? Instant.now() : row.startedAt());
        task.setFinishedAt(row.finishedAt());
        task.setExpiresAt(expiryOf(task.getStatus(), row.finishedAt()));
        task.setUpdatedAt(Instant.now());
        saveAndNotify(task);
    }

    private RowMapper<IntegrationRunProjection> integrationRunMapper() {
        return (rs, rowNum) -> new IntegrationRunProjection(
            rs.getString("run_id"),
            rs.getString("task_id"),
            rs.getString("task_name"),
            rs.getString("target_table"),
            uuid(rs, "tenant_id"),
            rs.getString("status"),
            longValue(rs, "rows_read"),
            longValue(rs, "rows_written"),
            rs.getString("error_code"),
            rs.getString("error_msg"),
            instant(rs, "started_at"),
            instant(rs, "finished_at")
        );
    }

    private RowMapper<SqlQueryProjection> sqlQueryMapper() {
        return (rs, rowNum) -> new SqlQueryProjection(
            rs.getString("query_id"),
            uuid(rs, "tenant_id"),
            uuid(rs, "user_id"),
            rs.getString("runner"),
            rs.getString("sql_text"),
            rs.getString("engine"),
            rs.getString("resource_group"),
            rs.getString("status"),
            longValue(rs, "duration_ms"),
            longValue(rs, "scan_bytes"),
            longValue(rs, "row_count"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            instant(rs, "created_at")
        );
    }

    private RowMapper<OrchestrationRunProjection> orchestrationRunMapper() {
        return (rs, rowNum) -> new OrchestrationRunProjection(
            rs.getString("run_id"),
            rs.getString("dag_id"),
            uuid(rs, "tenant_id"),
            rs.getString("dag_name"),
            rs.getString("dagster_job"),
            rs.getString("dagster_run_id"),
            rs.getString("trigger_type"),
            rs.getString("status"),
            instant(rs, "started_at"),
            instant(rs, "finished_at")
        );
    }

    private RowMapper<QualityResultProjection> qualityResultMapper() {
        return (rs, rowNum) -> new QualityResultProjection(
            rs.getString("result_id"),
            rs.getString("rule_id"),
            uuid(rs, "tenant_id"),
            rs.getString("target_fqn"),
            rs.getString("target_column"),
            rs.getString("rule_type"),
            rs.getString("severity"),
            booleanValue(rs, "passed"),
            bigDecimalValue(rs, "pass_rate"),
            longValue(rs, "failed_rows"),
            instant(rs, "checked_at")
        );
    }

    private String collectTitle(IntegrationRunProjection row) {
        String taskName = blankToNull(row.taskName());
        String targetTable = blankToNull(row.targetTable());
        if (taskName != null && targetTable != null) {
            return "采集任务 " + taskName + " -> " + targetTable;
        }
        if (taskName != null) {
            return "采集任务 " + taskName;
        }
        return "采集运行 " + row.runId();
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "RUNNING";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED", "SUCCESS" -> "SUCCEEDED";
            case "FAILED", "ERROR" -> "FAILED";
            case "CANCELLED", "CANCELED" -> "CANCELLED";
            case "QUEUED", "PENDING" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private int progressOf(String status) {
        return switch (status) {
            case "QUEUED" -> 10;
            case "RUNNING" -> 40;
            case "SUCCEEDED", "FAILED", "CANCELLED" -> 100;
            default -> 0;
        };
    }

    private String phaseOf(String status) {
        return switch (status) {
            case "QUEUED" -> "等待调度";
            case "RUNNING" -> "同步执行中";
            case "SUCCEEDED" -> "同步完成";
            case "FAILED" -> "同步失败";
            case "CANCELLED" -> "已取消";
            default -> "状态未知";
        };
    }

    private String detailOf(IntegrationRunProjection row, String status) {
        if ("FAILED".equals(status) && blankToNull(row.errorMessage()) != null) {
            return row.errorMessage();
        }
        Long written = row.rowsWritten();
        if (written != null && written > 0) {
            return written + " 行已写入";
        }
        return phaseOf(status);
    }

    private String sqlTitle(SqlQueryProjection row) {
        String sql = compact(row.sqlText(), 72);
        return sql == null ? "SQL 查询" : "SQL 查询 " + sql;
    }

    private String sqlPhaseOf(String status) {
        return switch (status) {
            case "QUEUED" -> "等待执行";
            case "RUNNING" -> "SQL 执行中";
            case "SUCCEEDED" -> "SQL 完成";
            case "FAILED" -> "SQL 失败";
            case "CANCELLED" -> "SQL 已取消";
            default -> "状态未知";
        };
    }

    private String sqlDetailOf(SqlQueryProjection row, String status) {
        if ("FAILED".equals(status) && blankToNull(row.errorMessage()) != null) {
            return row.errorMessage();
        }
        if ("CANCELLED".equals(status)) {
            return "查询已取消";
        }
        if (row.rowCount() != null) {
            return row.rowCount() + " 行结果";
        }
        if (row.scanBytes() != null) {
            return readableBytes(row.scanBytes()) + " 扫描";
        }
        String engine = blankToNull(row.engine()) == null ? "TRINO" : row.engine();
        String resourceGroup = blankToNull(row.resourceGroup());
        return resourceGroup == null ? engine : engine + " / " + resourceGroup;
    }

    private String orchestrationTitle(OrchestrationRunProjection row) {
        String name = blankToNull(row.dagName());
        return name == null ? "编排任务 " + row.runId() : "编排任务 " + name;
    }

    private String orchestrationPhaseOf(String status) {
        return switch (status) {
            case "QUEUED" -> "等待调度";
            case "RUNNING" -> "编排执行中";
            case "SUCCEEDED" -> "编排完成";
            case "FAILED" -> "编排失败";
            case "CANCELLED" -> "编排已取消";
            default -> "状态未知";
        };
    }

    private String orchestrationDetailOf(OrchestrationRunProjection row, String status) {
        if ("FAILED".equals(status)) {
            return "Dagster 运行失败";
        }
        String dagsterRunId = blankToNull(row.dagsterRunId());
        if (dagsterRunId != null) {
            return "Dagster run " + compact(dagsterRunId, 24);
        }
        String job = blankToNull(row.dagsterJob());
        String trigger = blankToNull(row.triggerType());
        if (job != null && trigger != null) {
            return job + " / " + trigger;
        }
        return orchestrationPhaseOf(status);
    }

    private String qualityTitle(QualityResultProjection row) {
        String target = blankToNull(row.targetFqn());
        String column = blankToNull(row.targetColumn());
        String ruleType = blankToNull(row.ruleType());
        String subject = target == null ? row.ruleId() : target + (column == null ? "" : "." + column);
        return "质量稽核 " + subject + (ruleType == null ? "" : " / " + ruleType);
    }

    private String qualityDetailOf(QualityResultProjection row) {
        String passRate = row.passRate() == null ? null : row.passRate().stripTrailingZeros().toPlainString() + "%";
        long failedRows = row.failedRows() == null ? 0L : row.failedRows();
        if (!Boolean.TRUE.equals(row.passed())) {
            return (passRate == null ? "" : "通过率 " + passRate + "，") + failedRows + " 行异常";
        }
        return passRate == null ? "稽核通过" : "通过率 " + passRate;
    }

    private Instant expiryOf(String status, Instant finishedAt) {
        if ("FAILED".equals(status) || "QUEUED".equals(status) || "RUNNING".equals(status)) {
            return null;
        }
        return (finishedAt == null ? Instant.now() : finishedAt).plus(10, ChronoUnit.MINUTES);
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "缺少租户上下文");
        }
        return tenantId;
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private int queryLimit(int limit) {
        return Math.min(Math.max(limit, DEFAULT_LIMIT), MAX_LIMIT);
    }

    private Instant finishedAtOf(String status, Instant startedAt, Long durationMs) {
        if ("QUEUED".equals(status) || "RUNNING".equals(status)) {
            return null;
        }
        Instant base = startedAt == null ? Instant.now() : startedAt;
        if (durationMs != null && durationMs > 0) {
            return base.plusMillis(durationMs);
        }
        return base;
    }

    private RunningTaskDTO toDTO(RunningTask t) {
        return new RunningTaskDTO(
            t.getId(),
            t.getSourceModule(),
            t.getTaskType(),
            t.getRefType(),
            t.getRefId(),
            t.getParentRefId(),
            t.getTitle(),
            t.getStatus(),
            t.getProgress(),
            t.getPhase(),
            t.getDetail(),
            t.getErrorCode(),
            t.getErrorMessage(),
            t.getLink(),
            t.getCancellable(),
            t.getCancelEndpoint(),
            t.getStartedAt(),
            t.getUpdatedAt(),
            t.getFinishedAt()
        );
    }

    private RunningTask saveAndNotify(RunningTask task) {
        RunningTask saved;
        try {
            saved = repo.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            saved = repo.findByTenantIdAndRefTypeAndRefId(
                    task.getTenantId(), task.getRefType(), task.getRefId())
                .map(existing -> {
                    copyMutableFields(task, existing);
                    return repo.saveAndFlush(existing);
                })
                .orElseThrow(() -> e);
        }
        notificationService.notifyTaskIfNeeded(saved);
        return saved;
    }

    private void copyMutableFields(RunningTask source, RunningTask target) {
        target.setUserId(source.getUserId());
        target.setSourceModule(source.getSourceModule());
        target.setTaskType(source.getTaskType());
        target.setParentRefId(source.getParentRefId());
        target.setTitle(source.getTitle());
        target.setStatus(source.getStatus());
        target.setProgress(source.getProgress());
        target.setPhase(source.getPhase());
        target.setDetail(source.getDetail());
        target.setErrorCode(source.getErrorCode());
        target.setErrorMessage(source.getErrorMessage());
        target.setLink(source.getLink());
        target.setCancellable(source.getCancellable());
        target.setCancelEndpoint(source.getCancelEndpoint());
        target.setStartedAt(source.getStartedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setFinishedAt(source.getFinishedAt());
        target.setExpiresAt(source.getExpiresAt());
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean booleanValue(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal bigDecimalValue(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String compact(String value, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String readableBytes(Long bytes) {
        if (bytes == null) {
            return "-";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value = value / 1024;
            unit++;
        }
        if (unit == 0) {
            return bytes + " " + units[unit];
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    record IntegrationRunProjection(
        String runId,
        String taskId,
        String taskName,
        String targetTable,
        UUID tenantId,
        String status,
        Long rowsRead,
        Long rowsWritten,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
    ) {}

    record SqlQueryProjection(
        String queryId,
        UUID tenantId,
        UUID userId,
        String runner,
        String sqlText,
        String engine,
        String resourceGroup,
        String status,
        Long durationMs,
        Long scanBytes,
        Long rowCount,
        String errorCode,
        String errorMessage,
        Instant createdAt
    ) {}

    record OrchestrationRunProjection(
        String runId,
        String dagId,
        UUID tenantId,
        String dagName,
        String dagsterJob,
        String dagsterRunId,
        String triggerType,
        String status,
        Instant startedAt,
        Instant finishedAt
    ) {}

    record QualityResultProjection(
        String resultId,
        String ruleId,
        UUID tenantId,
        String targetFqn,
        String targetColumn,
        String ruleType,
        String severity,
        Boolean passed,
        BigDecimal passRate,
        Long failedRows,
        Instant checkedAt
    ) {}
}
