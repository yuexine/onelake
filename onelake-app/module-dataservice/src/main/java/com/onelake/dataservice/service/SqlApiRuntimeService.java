package com.onelake.dataservice.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.dto.SqlApiDebugResultDTO;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
public class SqlApiRuntimeService {

    private final ApiDefinitionRepository apiRepo;

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

    public SqlApiDebugResultDTO debug(UUID apiId, Map<String, Object> params) {
        ApiDefinition api = apiRepo.findByTenantIdAndId(TenantContext.getTenantId(), apiId)
            .orElseThrow(() -> new BizException(40400, "API 不存在"));
        String sql = normalizeSql(api.getSelectSql());
        validateReadOnly(sql);
        BoundSql boundSql = bindNamedParams(sql, params == null ? Map.of() : params);
        return execute(boundSql);
    }

    private SqlApiDebugResultDTO execute(BoundSql boundSql) {
        Instant started = Instant.now();
        try {
            Class.forName("io.trino.jdbc.TrinoDriver");
            Properties properties = new Properties();
            properties.setProperty("user", trinoUser);
            if (trinoPassword != null && !trinoPassword.isBlank()) {
                properties.setProperty("password", trinoPassword);
            }
            try (var connection = DriverManager.getConnection(trinoJdbcUrl, properties);
                 PreparedStatement statement = connection.prepareStatement(boundSql.sql())) {
                statement.setQueryTimeout(timeoutSeconds);
                for (int i = 0; i < boundSql.values().size(); i++) {
                    statement.setObject(i + 1, boundSql.values().get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<SqlApiDebugResultDTO.SqlApiColumnDTO> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add(new SqlApiDebugResultDTO.SqlApiColumnDTO(
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
                    return new SqlApiDebugResultDTO(
                        columns,
                        rows,
                        Duration.between(started, Instant.now()).toMillis(),
                        rows.size(),
                        truncated
                    );
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50051, "SQL API 调试失败: " + rootMessage(e), e);
        }
    }

    private BoundSql bindNamedParams(String sql, Map<String, Object> params) {
        StringBuilder prepared = new StringBuilder(sql.length());
        List<Object> values = new ArrayList<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                prepared.append(ch);
                if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    prepared.append(sql.charAt(++i));
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                prepared.append(ch);
                continue;
            }
            if (ch == ':' && !inSingleQuote && !inDoubleQuote && i + 1 < sql.length()
                && isIdentifierStart(sql.charAt(i + 1)) && (i == 0 || sql.charAt(i - 1) != ':')) {
                int end = i + 2;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String name = sql.substring(i + 1, end);
                if (!params.containsKey(name)) {
                    throw new BizException(40052, "缺少 API 调试参数: " + name);
                }
                prepared.append('?');
                values.add(params.get(name));
                i = end - 1;
            } else {
                prepared.append(ch);
            }
        }
        return new BoundSql(prepared.toString(), values);
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
            throw new BizException(40050, "SQL API 调试仅允许只读查询");
        }
        String withoutTrailingSemicolon = normalized.endsWith(";")
            ? normalized.substring(0, normalized.length() - 1)
            : normalized;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new BizException(40050, "SQL API 调试不允许一次提交多条语句");
        }
        String padded = " " + withoutTrailingSemicolon + " ";
        for (String keyword : List.of(
            " insert ", " update ", " delete ", " merge ", " create ", " drop ",
            " alter ", " truncate ", " grant ", " revoke ", " call ", " execute "
        )) {
            if (padded.contains(keyword)) {
                throw new BizException(40050, "SQL API 调试仅允许只读查询");
            }
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(40051, "SQL API 草稿没有可调试 SQL");
        }
        return sql.trim();
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

    private boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record BoundSql(String sql, List<Object> values) {}
}
