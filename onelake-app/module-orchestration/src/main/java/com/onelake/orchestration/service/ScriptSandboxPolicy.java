package com.onelake.orchestration.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Python/Shell 沙箱的环境级开关与租户级准入策略。 */
@Component
public class ScriptSandboxPolicy {

    private final boolean enabled;
    private final boolean allTenants;
    private final Set<UUID> allowedTenantIds;

    public ScriptSandboxPolicy(
            @Value("${onelake.orchestration.script.enabled:false}") boolean enabled,
            @Value("${onelake.orchestration.script.allowed-tenant-ids:}") String allowedTenantIds) {
        this.enabled = enabled;
        String raw = allowedTenantIds == null ? "" : allowedTenantIds.trim();
        this.allTenants = "*".equals(raw);
        this.allowedTenantIds = allTenants || !StringUtils.hasText(raw)
                ? Set.of()
                : Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(value -> {
                        try {
                            return UUID.fromString(value);
                        } catch (IllegalArgumentException ex) {
                            throw new IllegalArgumentException(
                                    "Invalid script sandbox tenant UUID: " + value, ex);
                        }
                    })
                    .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabledFor(UUID tenantId) {
        return enabled && tenantId != null && (allTenants || allowedTenantIds.contains(tenantId));
    }

    public String blockedReason(UUID tenantId) {
        if (!enabled) {
            return "PYTHON/SHELL 沙箱能力默认关闭，请设置 ONELAKE_SCRIPT_SANDBOX_ENABLED=true";
        }
        if (tenantId == null || (!allTenants && !allowedTenantIds.contains(tenantId))) {
            return "当前租户未获 PYTHON/SHELL 沙箱能力授权，请配置 ONELAKE_SCRIPT_SANDBOX_ALLOWED_TENANTS";
        }
        return null;
    }
}
