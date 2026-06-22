package com.onelake.catalog.service.sql;

import com.onelake.catalog.config.TrinoConnectionFactory;
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
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.sql.ReadOnlySqlValidator;
import com.onelake.security.service.AclService;
import com.onelake.security.service.SecurityService;
import io.trino.jdbc.QueryStats;
import io.trino.jdbc.TrinoStatement;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlWorkbenchService {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final Pattern EXPLAIN_BYTES_PATTERN = Pattern.compile(
        "(?i)\\b(?:estimated(?:Scan)?Bytes|estimatedSizeInBytes|physicalInputBytes|processedBytes)\\b\"?\\s*[:=]\\s*\"?([0-9]+)"
    );
    private static final Pattern HUMAN_SIZE_PATTERN = Pattern.compile(
        "(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(TB|GB|MB|KB|B)\\b"
    );

    private final SqlQueryHistoryRepository historyRepo;
    private final SavedQueryRepository savedQueryRepo;
    private final SqlAssetSecurityService assetSecurityService;
    private final SecurityService securityService;
    private final AuditLogger auditLogger;
    private final AclService aclService;
    private final TrinoConnectionFactory trinoConnectionFactory;
    private final Map<UUID, QueryTask> queryTasks = new ConcurrentHashMap<>();
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool();

    public SqlWorkbenchService(
        SqlQueryHistoryRepository historyRepo,
        SavedQueryRepository savedQueryRepo,
        SqlAssetSecurityService assetSecurityService,
        SecurityService securityService,
        AuditLogger auditLogger,
        AclService aclService,
        TrinoConnectionFactory trinoConnectionFactory
    ) {
        this.historyRepo = historyRepo;
        this.savedQueryRepo = savedQueryRepo;
        this.assetSecurityService = assetSecurityService;
        this.securityService = securityService;
        this.auditLogger = auditLogger;
        this.aclService = aclService;
        this.trinoConnectionFactory = trinoConnectionFactory;
    }

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

    @Value("${onelake.dataplane.trino.export-max-rows:50000}")
    private int exportMaxRows;

    @Value("${onelake.dataplane.trino.max-interactive-rows:2000}")
    private int maxInteractiveRows;

    @Value("${onelake.dataplane.trino.max-running-per-user:5}")
    private int maxRunningPerUser;

    @Value("${onelake.dataplane.trino.task-timeout-minutes:30}")
    private int taskTimeoutMinutes;

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
        UUID tenantId = TenantContext.getTenantId();
        List<SavedQuery> all = savedQueryRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        List<SavedQuery> viewable = aclService.filterViewable(all, AclService.RESOURCE_SAVED_QUERY, new AclService.ResourceAccessor<SavedQuery>() {
            @Override public UUID ownerId(SavedQuery r) { return r.getOwnerId(); }
            @Override public UUID resourceId(SavedQuery r) { return r.getId(); }
        });
        return viewable.stream().map(this::toDTO).toList();
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
        boolean created = query.getId() == null;
        boolean wasShared = !created && query.isShared();
        boolean nowShared = request.shared();
        if (!created) {
            aclService.requireEdit(AclService.RESOURCE_SAVED_QUERY, query.getId(), query.getOwnerId());
        }
        if (created) {
            query.setTenantId(tenantId);
            query.setOwnerId(TenantContext.getUserId());
            query.setOwnerName(runnerName());
            query.setCreatedAt(now);
        }
        query.setName(name);
        query.setSqlText(sql);
        query.setShared(request.shared());
        query.setUpdatedAt(now);
        SavedQueryDTO dto = toDTO(savedQueryRepo.save(query));
        if (nowShared && !wasShared) {
            aclService.autoGrantOnShared(AclService.RESOURCE_SAVED_QUERY, query.getId());
        } else if (!nowShared && wasShared) {
            aclService.autoRevokeOnPrivate(AclService.RESOURCE_SAVED_QUERY, query.getId());
        }
        if (created) {
            auditLogger.auditCreate("SavedQuery", dto.id(), auditDetail("name", dto.name(), "shared", dto.shared()));
        } else {
            auditLogger.auditUpdate("SavedQuery", dto.id(), auditDetail("name", dto.name(), "shared", dto.shared()));
        }
        return dto;
    }

    @Transactional
    public SavedQueryDTO updateSavedQuery(UUID id, SqlSaveQueryRequest request) {
        String sql = normalizeSql(request.sql());
        validateReadOnly(sql);
        String name = request.name().trim();
        if (name.length() > 128) {
            throw new BizException(40041, "查询名称不能超过 128 个字符");
        }
        UUID tenantId = TenantContext.getTenantId();
        SavedQuery query = savedQueryRepo.findByTenantIdAndId(tenantId, id)
            .orElseThrow(() -> new BizException(40405, "保存查询不存在"));
        aclService.requireEdit(AclService.RESOURCE_SAVED_QUERY, id, query.getOwnerId());
        boolean wasShared = query.isShared();
        boolean nowShared = request.shared();
        query.setName(name);
        query.setSqlText(sql);
        query.setShared(request.shared());
        query.setUpdatedAt(Instant.now());
        SavedQueryDTO dto = toDTO(savedQueryRepo.save(query));
        if (nowShared && !wasShared) {
            aclService.autoGrantOnShared(AclService.RESOURCE_SAVED_QUERY, id);
        } else if (!nowShared && wasShared) {
            aclService.autoRevokeOnPrivate(AclService.RESOURCE_SAVED_QUERY, id);
        }
        auditLogger.auditUpdate("SavedQuery", id, auditDetail("name", dto.name(), "shared", dto.shared()));
        return dto;
    }

    @Transactional
    public void deleteSavedQuery(UUID id) {
        SavedQuery query = savedQueryRepo.findByTenantIdAndId(TenantContext.getTenantId(), id)
            .orElseThrow(() -> new BizException(40405, "保存查询不存在"));
        aclService.requireEdit(AclService.RESOURCE_SAVED_QUERY, id, query.getOwnerId());
        savedQueryRepo.delete(query);
        aclService.cleanupOnDelete(AclService.RESOURCE_SAVED_QUERY, id);
        auditLogger.auditDelete("SavedQuery", id);
    }

    public SqlEstimateDTO estimate(SqlExecuteRequest request) {
        String sql = normalizeSql(request.sql());
        validateReadOnly(sql);
        String engine = engineOf(request.engine());
        if (!"TRINO".equals(engine)) {
            return new SqlEstimateDTO(
                engine,
                null,
                false,
                "Spark batch 路由尚未接入可执行运行时；当前仅 Trino 可真实执行",
                "当前仅 Trino 查询链路具备执行、取消、历史和安全策略闭环"
            );
        }
        TrinoEstimate estimate = estimateTrinoScan(sql);
        Long estimatedScanBytes = estimate.scanBytes();
        boolean thresholdExceeded = estimatedScanBytes != null && estimatedScanBytes > scanThresholdBytes;
        String message = estimatedScanBytes == null
            ? "已通过只读校验；Trino EXPLAIN 未返回可解析扫描量"
                + (estimate.error() == null ? "" : "（" + estimate.error() + "）")
                + "，执行时继续采集真实 query stats"
            : "Trino EXPLAIN 估算扫描 " + humanBytes(estimatedScanBytes)
                + (thresholdExceeded ? "，超过阈值 " + humanBytes(scanThresholdBytes) : "，未超过阈值 " + humanBytes(scanThresholdBytes));
        return new SqlEstimateDTO(
            engine,
            estimatedScanBytes,
            thresholdExceeded,
            message,
            "AUTO 默认路由到 Trino；Spark batch 将在长查询异步运行时完成后启用"
        );
    }

    public SqlExecuteResultDTO execute(SqlExecuteRequest request) {
        String sql = normalizeSql(request.sql());
        String engine = engineOf(request.engine());
        int rowLimit = resolveInteractiveRowLimit(request.maxRows());
        SqlQueryHistory history = createHistory(sql, engine, request.resourceGroup());
        Instant started = Instant.now();
        QueryTask task = QueryTask.direct(history.getId(), sql, engine, request.resourceGroup(), started);
        try {
            validateReadOnly(sql);
            if (!"TRINO".equals(engine)) {
                throw new BizException(40042, "当前仅支持 Trino 查询执行");
            }
            task.securityContext = validateCatalogAccess(sql);
            enforceResourceControl(sql, request.confirmLargeQuery());
            SqlExecuteResultDTO result = executeTrino(task, rowLimit);
            history.setStatus(STATUS_SUCCEEDED);
            history.setDurationMs(result.durationMs());
            history.setTrinoQueryId(result.trinoQueryId());
            history.setScanBytes(result.scanBytes());
            history.setRowCount(result.rowCount());
            historyRepo.save(history);
            auditLogger.audit("RUN", "SqlQuery", history.getId().toString(), auditDetail(
                "mode", "sync",
                "sql", sql,
                "engine", engine,
                "status", STATUS_SUCCEEDED,
                "durationMs", result.durationMs(),
                "scanBytes", result.scanBytes(),
                "rowCount", result.rowCount()
            ));
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
            auditLogger.audit("RUN", "SqlQuery", history.getId().toString(), auditDetail(
                "mode", "sync",
                "sql", sql,
                "engine", engine,
                "status", STATUS_FAILED,
                "durationMs", history.getDurationMs(),
                "error", message
            ));
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
        int rowLimit = resolveInteractiveRowLimit(request.maxRows());
        validateReadOnly(sql);
        if (!"TRINO".equals(engine)) {
            throw new BizException(40042, "当前仅支持 Trino 查询执行");
        }
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        enforceConcurrencyLimit(tenantId, userId);
        SqlAssetSecurityService.SqlAssetSecurityContext securityContext = validateCatalogAccess(sql);
        enforceResourceControl(sql, request.confirmLargeQuery());
        SqlQueryHistory history = createHistory(sql, engine, request.resourceGroup());
        QueryTask task = new QueryTask(
            history.getId(),
            sql,
            engine,
            request.resourceGroup(),
            tenantId,
            userId,
            runnerName(),
            Instant.now(),
            securityContext
        );
        task.rowLimit = rowLimit;
        queryTasks.put(history.getId(), task);
        task.future = queryExecutor.submit(() -> runAsync(task));
        auditLogger.audit("RUN", "SqlQuery", history.getId().toString(), auditDetail(
            "mode", "async",
            "sql", sql,
            "engine", engine,
            "status", STATUS_RUNNING
        ));
        return runningResult(task);
    }

    private void enforceConcurrencyLimit(UUID tenantId, UUID userId) {
        if (maxRunningPerUser <= 0) return;
        long running = queryTasks.values().stream()
            .filter(task -> task.result == null && tenantId.equals(task.tenantId) && userId.equals(task.userId))
            .count();
        if (running >= maxRunningPerUser) {
            auditLogger.audit("REJECT", "SqlQuery", null, auditDetail(
                "reason", "concurrency-limit",
                "running", running,
                "limit", maxRunningPerUser
            ));
            throw new BizException(
                42901,
                "并发查询数超限：" + running + " 个进行中，上限 " + maxRunningPerUser + "。请先取消已完成或长时间运行的查询再提交。"
            );
        }
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
                auditLogger.audit("CANCEL", "SqlQuery", id.toString(), auditDetail("source", "history-orphan"));
                return historyResult(history);
            }
            return historyResult(history);
        }
        ensureTaskTenant(task);
        task.cancelRequested = true;
        Statement statement = task.statement;
        if (statement != null) {
            try {
                // Statement.cancel() 在 Trino JDBC 驱动内部会向 coordinator
                // 发送 DELETE /v1/query/{queryId}，等价于 killQuery —— 不需要额外的原生 killQuery 调用。
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
        auditLogger.audit("CANCEL", "SqlQuery", id.toString(), auditDetail(
            "trinoQueryId", task.trinoQueryId,
            "scanBytes", task.scanBytes
        ));
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
            if (task.securityContext == null) {
                task.securityContext = validateCatalogAccess(task.sql);
            }
            int rowLimit = task.rowLimit != null ? task.rowLimit : maxRows;
            SqlExecuteResultDTO result = executeTrino(task, rowLimit);
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
                String errorCode = (e instanceof BizException biz) ? String.valueOf(biz.getCode()) : "SQL_EXECUTION_FAILED";
                long durationMs = Duration.between(task.startedAt, Instant.now()).toMillis();
                task.result = failedResult(task, durationMs, message, errorCode);
                task.finishedAt = Instant.now();
                historyRepo.findByTenantIdAndId(task.tenantId, task.historyId).ifPresent(history -> {
                    history.setStatus(STATUS_FAILED);
                    history.setDurationMs(durationMs);
                    history.setTrinoQueryId(task.trinoQueryId);
                    history.setScanBytes(task.scanBytes);
                    history.setErrorCode(errorCode);
                    history.setErrorMessage(message);
                    historyRepo.save(history);
                });
            }
        } finally {
            TenantContext.clear();
        }
    }

    private SqlExecuteResultDTO executeTrino(QueryTask task) throws Exception {
        return executeTrino(task, maxRows);
    }

    private SqlExecuteResultDTO executeTrino(QueryTask task, int rowLimit) throws Exception {
        try (Connection connection = trinoConnectionFactory.getConnection();
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
                    if (rows.size() >= rowLimit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(columns.get(i - 1).name(), rs.getObject(i));
                    }
                    rows.add(row);
                }
                SecurityService.MaskingResult masking = securityService.maskRowsWithNotices(
                    rows,
                    task.securityContext.protectionsByColumn()
                );
                long durationMs = Duration.between(task.startedAt, Instant.now()).toMillis();
                return new SqlExecuteResultDTO(
                    task.historyId,
                    STATUS_SUCCEEDED,
                    task.trinoQueryId,
                    columns,
                    masking.rows(),
                    durationMs,
                    task.scanBytes,
                    (long) masking.rows().size(),
                    truncated,
                    null,
                    masking.maskedColumns(),
                    masking.securityNotices(),
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

    public void export(
        SqlExecuteRequest request,
        String format,
        Integer maxRowsOverride,
        HttpServletResponse response
    ) throws IOException {
        String sql = normalizeSql(request.sql());
        validateReadOnly(sql);
        String engine = engineOf(request.engine());
        if (!"TRINO".equals(engine)) {
            throw new BizException(40042, "当前仅支持 Trino 查询导出");
        }
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        // 与 submit 共享同一并发配额：用户同时进行的交互查询 + 导出总数受 max-running-per-user 限制。
        enforceConcurrencyLimit(tenantId, userId);
        SqlAssetSecurityService.SqlAssetSecurityContext securityContext = validateCatalogAccess(sql);
        enforceResourceControl(sql, request.confirmLargeQuery());

        String normalizedFormat = format == null ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(normalizedFormat) && !"tsv".equals(normalizedFormat)) {
            throw new BizException(40045, "导出格式仅支持 csv 或 tsv");
        }
        int rowLimit = exportMaxRows;
        if (maxRowsOverride != null && maxRowsOverride > 0) {
            rowLimit = Math.min(maxRowsOverride, exportMaxRows);
        }

        SqlQueryHistory history = createHistory(sql, engine, request.resourceGroup());
        Instant started = Instant.now();
        QueryTask task = QueryTask.direct(history.getId(), sql, engine, request.resourceGroup(), started);
        task.securityContext = securityContext;
        // 注册 QueryTask 使 /queries/{historyId}/cancel 可中断导出执行。
        // 注意：前端若想在 HTTP 响应中途触发取消，需改用 fetch() + ReadableStream
        // 以便在响应 header 到达时读取 X-Onelake-History-Id（Sprint 4 UX 优化）。
        queryTasks.put(history.getId(), task);

        try {
            SqlExecuteResultDTO result = executeTrino(task, rowLimit);
            history.setStatus(STATUS_SUCCEEDED);
            history.setDurationMs(result.durationMs());
            history.setTrinoQueryId(result.trinoQueryId());
            history.setScanBytes(result.scanBytes());
            history.setRowCount(result.rowCount());
            historyRepo.save(history);
            auditLogger.audit("EXPORT", "SqlQuery", history.getId().toString(), auditDetail(
                "sql", sql,
                "format", normalizedFormat,
                "rowCount", result.rowCount(),
                "scanBytes", result.scanBytes(),
                "truncated", result.truncated()
            ));

            response.setContentType(("csv".equals(normalizedFormat) ? "text/csv" : "text/tab-separated-values") + ";charset=utf-8");
            String filename = "onelake-query-" + history.getId() + "." + normalizedFormat;
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.addHeader("X-Onelake-History-Id", history.getId().toString());
            if (result.truncated()) {
                response.addHeader("X-Onelake-Export-Truncated", "true");
            }
            try (OutputStream os = response.getOutputStream();
                 Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                writeDelimited(writer, normalizedFormat, result);
            }
        } catch (Exception e) {
            String message = rootMessage(e);
            boolean cancelled = task.cancelRequested || (e instanceof SQLException && "Statement canceled".equalsIgnoreCase(message));
            history.setStatus(cancelled ? STATUS_CANCELLED : STATUS_FAILED);
            history.setDurationMs(Duration.between(started, Instant.now()).toMillis());
            history.setTrinoQueryId(task.trinoQueryId);
            history.setScanBytes(task.scanBytes);
            history.setErrorCode(cancelled ? "SQL_QUERY_CANCELLED" : (e instanceof BizException biz ? String.valueOf(biz.getCode()) : "SQL_EXPORT_FAILED"));
            history.setErrorMessage(cancelled ? "导出已取消" : message);
            historyRepo.save(history);
            auditLogger.audit("EXPORT", "SqlQuery", history.getId().toString(), auditDetail(
                "sql", sql,
                "format", normalizedFormat,
                "status", history.getStatus(),
                "error", history.getErrorMessage()
            ));
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\":" + (cancelled ? 49901 : 50042) + ",\"message\":\"" + history.getErrorMessage().replace("\"", "'") + "\"}");
            }
            if (e instanceof BizException biz) {
                throw biz;
            }
            if (e instanceof IOException io) {
                throw io;
            }
            if (cancelled) {
                return; // 取消不抛栈，避免污染日志
            }
            throw new BizException(50042, "SQL 导出失败: " + message, e);
        } finally {
            // 清理 task；endOfLife 时 ConcurrentMap 移除
            queryTasks.remove(history.getId());
        }
    }

    private void writeDelimited(Writer writer, String format, SqlExecuteResultDTO result) throws IOException {
        char delimiter = "csv".equals(format) ? ',' : '\t';
        List<SqlExecuteResultDTO.SqlColumnDTO> columns = result.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) writer.write(delimiter);
            writer.write(escapeDelimited(columns.get(i).name(), delimiter));
        }
        writer.write("\r\n");
        for (Map<String, Object> row : result.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) writer.write(delimiter);
                writer.write(escapeDelimited(cellValue(row.get(columns.get(i).name())), delimiter));
            }
            writer.write("\r\n");
        }
    }

    private String cellValue(Object value) {
        if (value == null) return "";
        if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp || value instanceof java.time.temporal.Temporal) {
            return value.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private String escapeDelimited(String value, char delimiter) {
        if (value == null) return "";
        boolean needsQuoting = value.indexOf(delimiter) >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private net.sf.jsqlparser.statement.Statement validateReadOnly(String sql) {
        return ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            sql,
            40040,
            "SQL 工作台仅允许只读查询",
            "SQL 工作台不允许一次提交多条语句"
        );
    }

    private SqlAssetSecurityService.SqlAssetSecurityContext validateCatalogAccess(String sql) {
        return assetSecurityService.validateAndPlan(sql, 40341, "SQL 引用资产未登记到 Catalog: ");
    }

    private void enforceResourceControl(String sql, Boolean confirmLargeQuery) {
        Long estimatedScanBytes = estimateTrinoScan(sql).scanBytes();
        if (estimatedScanBytes != null && estimatedScanBytes > scanThresholdBytes && !Boolean.TRUE.equals(confirmLargeQuery)) {
            throw new BizException(
                40044,
                "预计扫描量 " + humanBytes(estimatedScanBytes) + " 超过阈值 " + humanBytes(scanThresholdBytes) + "，请确认后再执行"
            );
        }
    }

    private Long estimateTrinoScanBytes(String sql) {
        return estimateTrinoScan(sql).scanBytes();
    }

    private TrinoEstimate estimateTrinoScan(String sql) {
        if (sql.regionMatches(true, 0, "SHOW", 0, 4) || sql.regionMatches(true, 0, "DESCRIBE", 0, 8)
            || sql.regionMatches(true, 0, "DESC", 0, 4)) {
            return new TrinoEstimate(null, "SHOW/DESCRIBE 不产生扫描量估算");
        }
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.min(timeoutSeconds, 10));
            try (ResultSet rs = statement.executeQuery("EXPLAIN (TYPE IO, FORMAT JSON) " + sql)) {
                StringBuilder explain = new StringBuilder();
                while (rs.next()) {
                    explain.append(rs.getString(1)).append('\n');
                }
                Long scanBytes = parseEstimatedScanBytes(explain.toString());
                return new TrinoEstimate(scanBytes, scanBytes == null ? "EXPLAIN 结果缺少 estimatedSizeInBytes" : null);
            }
        } catch (Exception e) {
            return new TrinoEstimate(null, rootMessage(e));
        }
    }

    static Long parseEstimatedScanBytes(String explain) {
        if (explain == null || explain.isBlank()) {
            return null;
        }
        long bytes = 0;
        Matcher numeric = EXPLAIN_BYTES_PATTERN.matcher(explain);
        while (numeric.find()) {
            bytes += Long.parseLong(numeric.group(1));
        }
        if (bytes > 0) {
            return bytes;
        }
        Matcher human = HUMAN_SIZE_PATTERN.matcher(explain);
        while (human.find()) {
            bytes += humanBytesToLong(Double.parseDouble(human.group(1)), human.group(2));
        }
        return bytes > 0 ? bytes : null;
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

    private String engineOf(String engine) {
        if (engine == null || engine.isBlank() || "AUTO".equalsIgnoreCase(engine)) {
            return "TRINO";
        }
        return engine.toUpperCase(Locale.ROOT);
    }

    private int resolveInteractiveRowLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return maxRows;
        }
        return Math.min(Math.max(requested, 1), maxInteractiveRows);
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
            query.getOwnerId(),
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

    /**
     * 构造审计详情 Map。使用 HashMap 而非 Map.of()，原因：
     * ① Map.of() 最多 10 键值对，扩展时会抛 IllegalArgumentException；
     * ② Map.of() 不允许 null 值，scanBytes 等字段在查询早期可能为 null。
     */
    private static Map<String, Object> auditDetail(Object... kv) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            detail.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return detail;
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

    private static long humanBytesToLong(double value, String unit) {
        return Math.round(value * switch (unit.toUpperCase(Locale.ROOT)) {
            case "TB" -> 1_099_511_627_776L;
            case "GB" -> 1_073_741_824L;
            case "MB" -> 1_048_576L;
            case "KB" -> 1024L;
            default -> 1L;
        });
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
        return new SqlExecuteResultDTO(historyId, STATUS_RUNNING, null, List.of(), List.of(), null, null, null, false, null, List.of(), List.of(), null);
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
            null,
            List.of(),
            List.of(),
            null
        );
    }

    private SqlExecuteResultDTO failedResult(QueryTask task, long durationMs, String message, String errorCode) {
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
            message,
            List.of(),
            List.of(),
            errorCode
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
            "查询已取消",
            List.of(),
            List.of(),
            "SQL_QUERY_CANCELLED"
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
            history.getErrorMessage(),
            List.of(),
            List.of(),
            history.getErrorCode()
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

    /**
     * 周期性清理：终结超过 task-timeout-minutes 仍未完成的任务。
     * 场景：Trino 连接断开、JDBC 驱动异常、worker 线程 hang 死等情况下，
     * QueryTask 可能既不进入 finished 状态也不抛异常，导致 ConcurrentMap 泄漏 + 并发额度被占用。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void cleanupStaleTasks() {
        if (taskTimeoutMinutes <= 0) return;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(taskTimeoutMinutes));
        List<QueryTask> stale = queryTasks.values().stream()
            .filter(task -> task.result == null && task.startedAt.isBefore(cutoff))
            .toList();
        for (QueryTask task : stale) {
            try {
                task.cancelRequested = true;
                Statement statement = task.statement;
                if (statement != null) {
                    try { statement.cancel(); } catch (SQLException ignored) {}
                }
                Future<?> future = task.future;
                if (future != null) {
                    future.cancel(true);
                }
                Instant finishedAt = Instant.now();
                long durationMs = Duration.between(task.startedAt, finishedAt).toMillis();
                task.result = cancelledResult(task, durationMs);
                task.finishedAt = finishedAt;
                historyRepo.findByTenantIdAndId(task.tenantId, task.historyId).ifPresent(history -> {
                    markCancelled(history, finishedAt);
                    history.setErrorMessage("查询超过 " + taskTimeoutMinutes + " 分钟未完成，已被系统强制取消");
                    historyRepo.save(history);
                });
                auditLogger.audit("REJECT", "SqlQuery", task.historyId.toString(), auditDetail(
                    "reason", "timeout-cleanup",
                    "durationMs", durationMs,
                    "trinoQueryId", task.trinoQueryId
                ));
                queryTasks.remove(task.historyId);
            } catch (Exception e) {
                // 单个任务清理失败不应中断其他任务的清理
            }
        }
    }

    private void ensureTaskTenant(QueryTask task) {
        if (!TenantContext.getTenantId().equals(task.tenantId)) {
            throw new BizException(40404, "SQL 查询不存在");
        }
    }

    static final class QueryTask {
        private final UUID historyId;
        private final String sql;
        private final String engine;
        private final String resourceGroup;
        private final UUID tenantId;
        private final UUID userId;
        private final String username;
        private final Instant startedAt;
        volatile Statement statement;
        volatile Future<?> future;
        volatile boolean cancelRequested;
        volatile String trinoQueryId;
        volatile Long scanBytes;
        volatile SqlExecuteResultDTO result;
        volatile Instant finishedAt;
        volatile SqlAssetSecurityService.SqlAssetSecurityContext securityContext;
        volatile Integer rowLimit;

        QueryTask(
            UUID historyId,
            String sql,
            String engine,
            String resourceGroup,
            UUID tenantId,
            UUID userId,
            String username,
            Instant startedAt,
            SqlAssetSecurityService.SqlAssetSecurityContext securityContext
        ) {
            this.historyId = historyId;
            this.sql = sql;
            this.engine = engine;
            this.resourceGroup = resourceGroup;
            this.tenantId = tenantId;
            this.userId = userId;
            this.username = username;
            this.startedAt = startedAt;
            this.securityContext = securityContext;
        }

        static QueryTask direct(UUID historyId, String sql, String engine, String resourceGroup, Instant startedAt) {
            return new QueryTask(historyId, sql, engine, resourceGroup, null, null, null, startedAt, null);
        }
    }

    private record TrinoEstimate(Long scanBytes, String error) {}
}
