package com.onelake.dataservice.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.sql.ReadOnlySqlValidator;
import com.onelake.catalog.service.sql.SqlAssetSecurityService;
import com.onelake.dataservice.domain.entity.ApiCallLog;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.domain.entity.AppKey;
import com.onelake.dataservice.domain.entity.QuotaUsage;
import com.onelake.dataservice.dto.SqlApiDebugResultDTO;
import com.onelake.dataservice.repository.ApiCallLogRepository;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import com.onelake.dataservice.repository.AppKeyRepository;
import com.onelake.dataservice.repository.QuotaUsageRepository;
import com.onelake.dataservice.repository.SubscriptionRepository;
import com.onelake.security.service.SecurityService;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SqlApiRuntimeService {

    private final ApiDefinitionRepository apiRepo;
    private final AppKeyRepository appKeyRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final ApiCallLogRepository callLogRepo;
    private final QuotaUsageRepository quotaUsageRepo;
    private final SqlAssetSecurityService assetSecurityService;
    private final SecurityService securityService;

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
        SqlAssetSecurityService.SqlAssetSecurityContext securityContext = validateCatalogAccess(sql);
        return execute(boundSql, securityContext);
    }

    public SqlApiDebugResultDTO invoke(
        String apiPath,
        Map<String, Object> params,
        String appKeyValue,
        String requestIp
    ) {
        Instant started = Instant.now();
        ApiDefinition api = null;
        AppKey appKey = null;
        int statusCode = 200;
        try {
            appKey = loadActiveAppKey(appKeyValue);
            TenantContext.setTenantId(appKey.getTenantId());
            TenantContext.setUserId(appKey.getOwnerId());
            TenantContext.setUsername("appkey:" + appKey.getAppKey());
            api = apiRepo.findByTenantIdAndApiPath(appKey.getTenantId(), normalizeApiPath(apiPath))
                .orElseThrow(() -> new BizException(40400, "API 不存在"));
            if (!"PUBLISHED".equalsIgnoreCase(api.getStatus())) {
                throw new BizException(40400, "API 未发布或已下线");
            }
            requireSubscription(appKey, api);
            reserveQuota(appKey, api);

            String sql = normalizeSql(api.getSelectSql());
            validateReadOnly(sql);
            BoundSql boundSql = bindNamedParams(sql, params == null ? Map.of() : params);
            SqlAssetSecurityService.SqlAssetSecurityContext securityContext = validateCatalogAccess(sql);
            return execute(boundSql, securityContext);
        } catch (BizException e) {
            statusCode = statusCodeOf(e.getCode());
            throw e;
        } catch (Exception e) {
            statusCode = 500;
            throw new BizException(50052, "SQL API 调用失败: " + rootMessage(e), e);
        } finally {
            if (api != null) {
                recordCall(api, appKey, statusCode, started, requestIp);
            }
            TenantContext.clear();
        }
    }

    private SqlApiDebugResultDTO execute(
        BoundSql boundSql,
        SqlAssetSecurityService.SqlAssetSecurityContext securityContext
    ) {
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
                    SecurityService.MaskingResult masking = securityService.maskRowsWithNotices(
                        rows,
                        securityContext.protectionsByColumn()
                    );
                    return new SqlApiDebugResultDTO(
                        columns,
                        masking.rows(),
                        Duration.between(started, Instant.now()).toMillis(),
                        masking.rows().size(),
                        truncated,
                        masking.maskedColumns(),
                        masking.securityNotices()
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

    private Statement validateReadOnly(String sql) {
        return ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            sql,
            40050,
            "SQL API 调试仅允许只读查询",
            "SQL API 调试不允许一次提交多条语句"
        );
    }

    private SqlAssetSecurityService.SqlAssetSecurityContext validateCatalogAccess(String sql) {
        return assetSecurityService.validateAndPlan(sql, 40351, "SQL API 引用资产未登记到 Catalog: ");
    }

    private AppKey loadActiveAppKey(String appKeyValue) {
        if (appKeyValue == null || appKeyValue.isBlank()) {
            throw new BizException(40100, "缺少 X-App-Key");
        }
        AppKey appKey = appKeyRepo.findByAppKey(appKeyValue)
            .orElseThrow(() -> new BizException(40100, "AppKey 不存在或已失效"));
        if (!"ACTIVE".equalsIgnoreCase(appKey.getStatus())
            || (appKey.getExpiresAt() != null && appKey.getExpiresAt().isBefore(Instant.now()))) {
            throw new BizException(40100, "AppKey 不存在或已失效");
        }
        return appKey;
    }

    private void requireSubscription(AppKey appKey, ApiDefinition api) {
        boolean approved = subscriptionRepo.findByApiIdAndAppKeyIdAndStatus(api.getId(), appKey.getId(), "APPROVED")
            .stream()
            .findFirst()
            .isPresent();
        if (!approved) {
            throw new BizException(40352, "AppKey 未订阅或未获批该 API");
        }
    }

    private void reserveQuota(AppKey appKey, ApiDefinition api) {
        if (appKey.getQuotaDaily() == null || appKey.getQuotaDaily() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        QuotaUsage usage = quotaUsageRepo.findByAppKeyIdAndApiIdAndStatDate(appKey.getId(), api.getId(), today)
            .orElseGet(() -> {
                QuotaUsage next = new QuotaUsage();
                next.setAppKeyId(appKey.getId());
                next.setApiId(api.getId());
                next.setStatDate(today);
                next.setCallCount(0L);
                return next;
            });
        if (usage.getCallCount() >= appKey.getQuotaDaily()) {
            throw new BizException(42900, "API 日配额已用尽");
        }
        usage.setCallCount(usage.getCallCount() + 1);
        quotaUsageRepo.save(usage);
    }

    private void recordCall(ApiDefinition api, AppKey appKey, int statusCode, Instant started, String requestIp) {
        ApiCallLog log = new ApiCallLog();
        log.setApiId(api.getId());
        log.setAppKeyId(appKey == null ? null : appKey.getId());
        log.setStatusCode(statusCode);
        log.setLatencyMs((int) Duration.between(started, Instant.now()).toMillis());
        log.setRequestIp(requestIp);
        callLogRepo.save(log);
    }

    private String normalizeApiPath(String apiPath) {
        if (apiPath == null || apiPath.isBlank()) {
            throw new BizException(40400, "API 不存在");
        }
        return apiPath.startsWith("/") ? apiPath : "/" + apiPath;
    }

    private int statusCodeOf(int code) {
        if (code >= 50000) return 500;
        if (code == 40400) return 404;
        if (code == 40100) return 401;
        if (code >= 40300 && code < 40400) return 403;
        if (code == 42900) return 429;
        return 400;
    }

    private String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(40051, "SQL API 草稿没有可调试 SQL");
        }
        return sql.trim();
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
