package com.onelake.orchestration.dto;

/**
 * 安装或锁定算子版本的请求体。
 */
public record OperatorInstallRequest(
    String pinnedVersion
) {
}
