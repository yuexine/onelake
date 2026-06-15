package com.onelake.common.context;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户上下文（ThreadLocal）。
 * 所有业务表带 tenant_id，由拦截器解析 JWT realm 自动注入。
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static UUID getTenantId() {
        return TENANT.get();
    }

    public static void setTenantId(UUID tenantId) {
        TENANT.set(tenantId);
    }

    public static UUID getUserId() {
        return USER.get();
    }

    public static void setUserId(UUID userId) {
        USER.set(userId);
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void setUsername(String name) {
        USERNAME.set(name);
    }

    public static String getTraceId() {
        return Optional.ofNullable(TRACE_ID.get()).orElse("-");
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static void clear() {
        TENANT.remove();
        USER.remove();
        USERNAME.remove();
        TRACE_ID.remove();
    }
}
