package com.onelake.orchestration.dto;

/**
 * 安装或锁定算子版本的请求体。
 *
 * @param pinnedVersion 固定版本；为空表示跟随最新版本
 */
public record OperatorInstallRequest(
    String pinnedVersion
) {
}
