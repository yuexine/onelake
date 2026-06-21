package com.onelake.catalog.service.sql;

import com.onelake.catalog.domain.entity.sql.SavedQuery;
import com.onelake.catalog.domain.entity.sql.SqlQueryHistory;
import com.onelake.catalog.dto.sql.SavedQueryDTO;
import com.onelake.catalog.dto.sql.SqlEstimateDTO;
import com.onelake.catalog.dto.sql.SqlExecuteRequest;
import com.onelake.catalog.dto.sql.SqlExecuteResultDTO;
import com.onelake.catalog.dto.sql.SqlQueryHistoryDTO;
import com.onelake.catalog.dto.sql.SqlSaveQueryRequest;
import com.onelake.catalog.repository.sql.SavedQueryRepository;
import com.onelake.catalog.repository.sql.SqlQueryHistoryRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import io.trino.jdbc.QueryStats;
import io.trino.jdbc.TrinoStatement;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
public class SqlWorkbenchService {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final SqlQueryHistoryRepository historyRepo;
    private final SavedQueryRepository savedQueryRepo;
    private final Map<UUID, QueryTask> queryTasks = new ConcurrentHashMap<>();
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool();

    @Value("${onelake.dataplane.trino.jdbc-url:jdbc:trino://localhost:18080/iceberg}")
    private String trinoJdbcUrl;

    @Value("${onelake.dataplane.trino.user:onelake}")
    private String trinoUser;

    @Value("${onelake.dataplane.trino.password:}")
    private String trinoPassword;

    @Value("${onelake.dataplane.trino.max-rows:200}")
    private int maxRows;

    @Value("${onelake.dataplane.trino.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${onelake.dataplane.trino.scan-threshold-bytes:1099511627776}")
    private long scanThresholdBytes;

    @Value("${onelake.dataplane.trino.result-retention-minutes:15}")
    private long resultRetentionMinutes;

    @PreDestroy
    public void shutdown() {
        queryExecutor.shutdownNow();
    }

    @Transactional(readOnly = true)
    public List<SqlQueryHistoryDTO> history() {
        return historyRepo.findTop50ByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId())
            .stream()
            .map(this::toDTO)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SavedQueryDTO> savedQueries() {
        return savedQueryRepo.findByTenantIdOrderByUpdatedAtDesc(TenantContext.getTenantId())
            .stream()
            .map(this::toDTO)
            .toList();
    }

    @Transactional
    public SavedQueryDTO saveQuery(SqlSaveQueryRequest request) {
        String sql = normalizeSql(request.sql());
        validateReadOnly(sql);
        String name = request.name().trim();
        if (name.length() > 128) {
            throw new BizException(40041, "查询名称不能超过 128 个字符");
        }
        UUID tenantId = TenantContext.getTenantId();
        SavedQuery query = savedQueryRepo.findByTenantIdAndName(tenantId, name)
            .orElseGet(SavedQuery::new);
        Instant now = Instant.now();
        if (query.getId() == null) {
            query.setTenantId(tenantId);
            query.setOwnerId(TenantContext.getUserId());
            query.setOwnerName(runnerName());
            query.setCreatedAt(now);
        }
        query.setName(name);
        query.setSqlText(sql);
        query.setShared(request.shared());
        query.setUpdatedAt(now);
        return toDTO(savedQueryRepo.save(query));
    }

    public SqlEstimateDTO estimate(SqlExecuteRequest request) {
        String sql = normalizeSql(request.sql());
        validateReadOnly(sql);
        String engine = engineOf(request.engine());
        return new SqlEstimateDTO(
            engine,
            null,
            false,
            "已通过只读校验；扫描量需执行引擎返回，阈值 " + humanBytes(scanThresholdBytes)
        );
    }

    public SqlExecuteResultDTO execute(SqlExecuteRequest request) {
        String sql = normalizeSql(request.sql());
        String engine = engineOf(request.engine());
        SqlQueryHistory history = createHistory(sql, engine, request.resourceGroup());
        Instant started = Instant.now();
        QueryTask task = QueryTask.direct(history.getId(), sql, engine, request.resourceGroup(), started);
        try {
            validateReadOnly(sql);
            if (!"TRINO".equals(engine)) {
                throw new BizException(40042, "当前仅支持 Trino 查询执行");
            }
            SqlExecuteResultDTO result = executeTrino(task);
            history.setStatus(STATUS_SUCCEEDED);
            history.setDurationMs(result.durationMs());
            history.setTrinoQueryId(result.trinoQueryId());
            history.setScanBytes(result.scanBytes());
            history.setRowCount(result.rowCount());
            historyRepo.save(history);
            return result;
        } catch (Exception e) {
            String message = rootMessage(e);
            history.setStatus(STATUS_FAILED);
            history.setDurationMs(Duration.between(started, Instant.now()).toMillis());
            history.setTrinoQueryId(task.trinoQueryId);
            history.setScanBytes(task.scanBytes);
            history.setErrorCode(e instanceof BizException biz ? String.valueOf(biz.getCode()) : "SQL_EXECUTION_FAILED");
            history.setErrorMessage(message);
            historyRepo.save(history);
            if (e instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(50041, "SQL 执行失败: " + message, e);
        }
    }

    public SqlExecuteResultDTO submit(SqlExecuteRequest request) {
        cleanupFinishedTasks();
        String sql = normalizeSql(request.sql());
        String engine = engineOf(request.engine());
        SqlQueryHistory history = createHistory(sql, engine, request.resourceGroup());
        QueryTask task = new QueryTask(
            history.getId(),
            sql,
            engine,
            request.resourceGroup(),
            TenantContext.getTenantId(),
            TenantContext.getUserId(),
            runnerName(),
            Instant.now()
        );
        queryTasks.put(history.getId(), task);
        task.future = queryExecutor.submit(() -> runAsync(task));
        return runningResult(task);
    }

    @Transactional(readOnly = true)
    public SqlExecuteResultDTO query(UUID id) {
        QueryTask task = queryTasks.get(id);
        if (task != null) {
            ensureTaskTenant(task);
            SqlExecuteResultDTO result = task.result;
            return result != null ? result : runningResult(task);
        }
        SqlQueryHistory history = historyRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
            .orElseThrow(() -> new BizException(40404, "SQL 查询不存在"));
        return historyResult(history);
    }

    public SqlExecuteResultDTO cancel(UUID id) {
        QueryTask task = queryTasks.get(id);
        if (task == null) {
            SqlQueryHistory history = historyRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new BizException(40404, "SQL 查询不存在"));
            if (STATUS_RUNNING.equals(history.getStatus())) {
                markCancelled(history, Instant.now());
                historyRepo.save(history);
                return historyResult(history);
            }
            return historyResult(history);
        }
        ensureTaskTenant(task);
        task.cancelRequested = true;
        Statement statement = task.statement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // The worker will persist the final cancellation/failure state.
            }
        }
        Future<?> future = task.future;
        if (future != null) {
            future.cancel(true);
        }
        markTaskCancelled(task);
        return task.result;
    }

    private void runAsync(QueryTask task) {
        TenantContext.setTenantId(task.tenantId);
        TenantContext.setUserId(task.userId);
        TenantContext.setUsername(task.username);
        try {
            validateReadOnly(task.sql);
            if (!"TRINO".equals(task.engine)) {
                throw new BizException(40042, "当前仅支持 Trino 查询执行");
            }
            SqlExecuteResultDTO result = executeTrino(task);
            if (task.cancelRequested || Thread.currentThread().isInterrupted()) {
                markTaskCancelled(task);
            } else {
                task.result = result;
                task.finishedAt = Instant.now();
                historyRepo.findByTenantIdAndId(task.tenantId, task.historyId).ifPresent(history -> {
                    history.setStatus(STATUS_SUCCEEDED);
                    history.setDurationMs(result.durationMs());
                    history.setTrinoQueryId(result.trinoQueryId());
                    history.setScanBytes(result.scanBytes());
                    history.setRowCount(result.rowCount());
                    historyRepo.save(history);
                });
            }
        } catch (Exception e) {
            if (task.cancelRequested || Thread.currentThread().isInterrupted()) {
                markTaskCancelled(task);
            } else {
                String message = rootMessage(e);
                long durationMs = Duration.between(task.startedAt, Instant.now()).toMillis();
                task.result = failedResult(task, durationMs, message);
                task.finishedAt = Instant.now();
                historyRepo.findByTenantIdAndId(task.tenantId, task.historyId).ifPresent(history -> {
                    history.setStatus(STATUS_FAILED);
                    history.setDurationMs(durationMs);
                    history.setTrinoQueryId(task.trinoQueryId);
                    history.setScanBytes(task.scanBytes);
                    history.setErrorCode(e instanceof BizException biz ? String.valueOf(biz.getCode()) : "SQL_EXECUTION_FAILED");
                    history.setErrorMessage(message);
                    historyRepo.save(history);
                });
            }
        } finally {
            TenantContext.clear();
        }
    }

    private SqlExecuteResultDTO executeTrino(QueryTask task) throws Exception {
        Class.forName("io.trino.jdbc.TrinoDriver");
        Properties properties = new Properties();
        properties.setProperty("user", trinoUser);
        if (trinoPassword != null && !trinoPassword.isBlank()) {
            properties.setProperty("password", trinoPassword);
        }
        try (var connection = DriverManager.getConnection(trinoJdbcUrl, properties);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            task.statement = statement;
            attachProgressMonitor(statement, task);
            try (ResultSet rs = statement.executeQuery(task.sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<SqlExecuteResultDTO.SqlColumnDTO> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(new SqlExecuteResultDTO.SqlColumnDTO(
                        meta.getColumnLabel(i),
                        meta.getColumnTypeName(i)
                    ));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(columns.get(i - 1).name(), rs.getObject(i));
                    }
                    rows.add(row);
                }
                long durationMs = Duration.between(task.startedAt, Instant.now()).toMillis();
                return new SqlExecuteResultDTO(
                    task.historyId,
                    STATUS_SUCCEEDED,
                    task.trinoQueryId,
                    columns,
                    rows,
                    durationMs,
                    task.scanBytes,
                    (long) rows.size(),
                    truncated,
                    null
                );
            } finally {
                clearProgressMonitor(statement);
                task.statement = null;
            }
        }
    }

    @Transactional
    protected SqlQueryHistory createHistory(String sql, String engine, String resourceGroup) {
        SqlQueryHistory history = new SqlQueryHistory();
        history.setTenantId(TenantContext.getTenantId());
        history.setUserId(TenantContext.getUserId());
        history.setRunner(runnerName());
        history.setSqlText(sql);
        history.setEngine(engine);
        history.setResourceGroup(resourceGroup);
        history.setStatus("RUNNING");
        history.setCreatedAt(Instant.now());
        return historyRepo.save(history);
    }

    private void validateReadOnly(String sql) {
        String normalized = stripLeadingComments(sql).trim().toLowerCase(Locale.ROOT);
        boolean allowed = normalized.startsWith("select ")
            || normalized.startsWith("with ")
            || normalized.startsWith("show ")
            || normalized.startsWith("describe ")
            || normalized.startsWith("desc ")
            || normalized.startsWith("explain ");
        if (!allowed) {
            throw new BizException(40040, "SQL 工作台仅允许只读查询");
        }
        String withoutTrailingSemicolon = normalized.endsWith(";")
            ? normalized.substring(0, normalized.length() - 1)
            : normalized;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new BizException(40040, "SQL 工作台不允许一次提交多条语句");
        }
        List<String> forbidden = List.of(
            " insert ", " update ", " delete ", " merge ", " create ", " drop ",
            " alter ", " truncate ", " grant ", " revoke ", " call ", " execute "
        );
        String padded = " " + withoutTrailingSemicolon + " ";
        for (String keyword : forbidden) {
            if (padded.contains(keyword)) {
                throw new BizException(40040, "SQL 工作台仅允许只读查询");
            }
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(40000, "SQL 不能为空");
        }
        String normalized = sql.trim();
        if (normalized.length() > 100_000) {
            throw new BizException(40043, "SQL 长度不能超过 100000 字符");
        }
        return normalized;
    }

    private String stripLeadingComments(String sql) {
        String current = sql;
        boolean changed;
        do {
            changed = false;
            current = current.stripLeading();
            if (current.startsWith("--")) {
                int end = current.indexOf('\n');
                current = end >= 0 ? current.substring(end + 1) : "";
                changed = true;
            } else if (current.startsWith("/*")) {
                int end = current.indexOf("*/");
                current = end >= 0 ? current.substring(end + 2) : "";
                changed = true;
            }
        } while (changed);
        return current;
    }

    private String engineOf(String engine) {
        if (engine == null || engine.isBlank() || "AUTO".equalsIgnoreCase(engine)) {
            return "TRINO";
        }
        return engine.toUpperCase(Locale.ROOT);
    }

    private SqlQueryHistoryDTO toDTO(SqlQueryHistory history) {
        return new SqlQueryHistoryDTO(
            history.getId(),
            history.getRunner(),
            history.getCreatedAt(),
            history.getTrinoQueryId(),
            history.getScanBytes(),
            history.getDurationMs(),
            STATUS_SUCCEEDED.equals(history.getStatus()),
            history.getStatus(),
            history.getSqlText(),
            history.getErrorMessage()
        );
    }

    private SavedQueryDTO toDTO(SavedQuery query) {
        return new SavedQueryDTO(
            query.getId(),
            query.getName(),
            query.getOwnerName(),
            query.isShared(),
            query.getSqlText(),
            query.getUpdatedAt()
        );
    }

    private String runnerName() {
        String username = TenantContext.getUsername();
        return username == null || username.isBlank() ? "unknown" : username;
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1_099_511_627_776L) {
            return String.format(Locale.ROOT, "%.2f TB", bytes / 1_099_511_627_776.0);
        }
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0);
        }
        return bytes + " B";
    }

    private void attachProgressMonitor(Statement statement, QueryTask task) {
        try {
            statement.unwrap(TrinoStatement.class).setProgressMonitor(stats -> updateTaskStats(task, stats));
        } catch (SQLException ignored) {
            // Non-Trino wrappers can still execute; stats stay empty in that case.
        }
    }

    private void clearProgressMonitor(Statement statement) {
        try {
            statement.unwrap(TrinoStatement.class).clearProgressMonitor();
        } catch (SQLException ignored) {
            // Best-effort cleanup only.
        }
    }

    private void updateTaskStats(QueryTask task, QueryStats stats) {
        if (stats == null) {
            return;
        }
        task.trinoQueryId = stats.getQueryId();
        long scanBytes = Math.max(stats.getProcessedBytes(), stats.getPhysicalInputBytes());
        if (scanBytes > 0) {
            task.scanBytes = scanBytes;
        }
    }

    private SqlExecuteResultDTO runningResult(UUID historyId) {
        return new SqlExecuteResultDTO(historyId, STATUS_RUNNING, null, List.of(), List.of(), null, null, null, false, null);
    }

    private SqlExecuteResultDTO runningResult(QueryTask task) {
        return new SqlExecuteResultDTO(
            task.historyId,
            STATUS_RUNNING,
            task.trinoQueryId,
            List.of(),
            List.of(),
            null,
            task.scanBytes,
            null,
            false,
            null
        );
    }

    private SqlExecuteResultDTO failedResult(QueryTask task, long durationMs, String message) {
        return new SqlExecuteResultDTO(
            task.historyId,
            STATUS_FAILED,
            task.trinoQueryId,
            List.of(),
            List.of(),
            durationMs,
            task.scanBytes,
            null,
            false,
            message
        );
    }

    private SqlExecuteResultDTO cancelledResult(QueryTask task, long durationMs) {
        return new SqlExecuteResultDTO(
            task.historyId,
            STATUS_CANCELLED,
            task.trinoQueryId,
            List.of(),
            List.of(),
            durationMs,
            task.scanBytes,
            null,
            false,
            "查询已取消"
        );
    }

    private SqlExecuteResultDTO historyResult(SqlQueryHistory history) {
        return new SqlExecuteResultDTO(
            history.getId(),
            history.getStatus(),
            history.getTrinoQueryId(),
            List.of(),
            List.of(),
            history.getDurationMs(),
            history.getScanBytes(),
            history.getRowCount(),
            false,
            history.getErrorMessage()
        );
    }

    private void markTaskCancelled(QueryTask task) {
        if (STATUS_CANCELLED.equals(task.result == null ? null : task.result.status())) {
            return;
        }
        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(task.startedAt, finishedAt).toMillis();
        task.result = cancelledResult(task, durationMs);
        task.finishedAt = finishedAt;
        historyRepo.findByTenantIdAndId(task.tenantId, task.historyId).ifPresent(history -> {
            markCancelled(history, finishedAt);
            history.setTrinoQueryId(task.trinoQueryId);
            history.setScanBytes(task.scanBytes);
            historyRepo.save(history);
        });
    }

    private void markCancelled(SqlQueryHistory history, Instant finishedAt) {
        history.setStatus(STATUS_CANCELLED);
        history.setDurationMs(Duration.between(history.getCreatedAt(), finishedAt).toMillis());
        history.setErrorCode("SQL_QUERY_CANCELLED");
        history.setErrorMessage("查询已取消");
    }

    private void cleanupFinishedTasks() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(resultRetentionMinutes));
        queryTasks.values().stream()
            .filter(task -> task.finishedAt != null && task.finishedAt.isBefore(cutoff))
            .sorted(Comparator.comparing(task -> task.finishedAt))
            .map(task -> task.historyId)
            .forEach(queryTasks::remove);
    }

    private void ensureTaskTenant(QueryTask task) {
        if (!TenantContext.getTenantId().equals(task.tenantId)) {
            throw new BizException(40404, "SQL 查询不存在");
        }
    }

    private static final class QueryTask {
        private final UUID historyId;
        private final String sql;
        private final String engine;
        private final String resourceGroup;
        private final UUID tenantId;
        private final UUID userId;
        private final String username;
        private final Instant startedAt;
        private volatile Statement statement;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;
        private volatile String trinoQueryId;
        private volatile Long scanBytes;
        private volatile SqlExecuteResultDTO result;
        private volatile Instant finishedAt;

        private QueryTask(
            UUID historyId,
            String sql,
            String engine,
            String resourceGroup,
            UUID tenantId,
            UUID userId,
            String username,
            Instant startedAt
        ) {
            this.historyId = historyId;
            this.sql = sql;
            this.engine = engine;
            this.resourceGroup = resourceGroup;
            this.tenantId = tenantId;
            this.userId = userId;
            this.username = username;
            this.startedAt = startedAt;
        }

        private static QueryTask direct(UUID historyId, String sql, String engine, String resourceGroup, Instant startedAt) {
            return new QueryTask(historyId, sql, engine, resourceGroup, null, null, null, startedAt);
        }
    }
}
