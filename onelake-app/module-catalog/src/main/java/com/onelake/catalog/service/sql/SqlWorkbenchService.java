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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SqlWorkbenchService {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final SqlQueryHistoryRepository historyRepo;
    private final SavedQueryRepository savedQueryRepo;

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
        try {
            validateReadOnly(sql);
            if (!"TRINO".equals(engine)) {
                throw new BizException(40042, "当前仅支持 Trino 查询执行");
            }
            SqlExecuteResultDTO result = executeTrino(sql, history.getId(), started);
            history.setStatus(STATUS_SUCCEEDED);
            history.setDurationMs(result.durationMs());
            history.setRowCount(result.rowCount());
            historyRepo.save(history);
            return result;
        } catch (Exception e) {
            String message = rootMessage(e);
            history.setStatus(STATUS_FAILED);
            history.setDurationMs(Duration.between(started, Instant.now()).toMillis());
            history.setErrorCode(e instanceof BizException biz ? String.valueOf(biz.getCode()) : "SQL_EXECUTION_FAILED");
            history.setErrorMessage(message);
            historyRepo.save(history);
            if (e instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(50041, "SQL 执行失败: " + message, e);
        }
    }

    private SqlExecuteResultDTO executeTrino(String sql, UUID historyId, Instant started) throws Exception {
        Class.forName("io.trino.jdbc.TrinoDriver");
        Properties properties = new Properties();
        properties.setProperty("user", trinoUser);
        if (trinoPassword != null && !trinoPassword.isBlank()) {
            properties.setProperty("password", trinoPassword);
        }
        try (var connection = DriverManager.getConnection(trinoJdbcUrl, properties);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<SqlExecuteResultDTO.SqlColumnDTO> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(new SqlExecuteResultDTO.SqlColumnDTO(
                        meta.getColumnLabel(i),
                        meta.getColumnTypeName(i)
                    ));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                long totalRows = 0;
                while (rs.next()) {
                    totalRows++;
                    if (rows.size() >= maxRows) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(columns.get(i - 1).name(), rs.getObject(i));
                    }
                    rows.add(row);
                }
                long durationMs = Duration.between(started, Instant.now()).toMillis();
                return new SqlExecuteResultDTO(
                    historyId,
                    STATUS_SUCCEEDED,
                    columns,
                    rows,
                    durationMs,
                    null,
                    totalRows,
                    totalRows > rows.size(),
                    null
                );
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
}
