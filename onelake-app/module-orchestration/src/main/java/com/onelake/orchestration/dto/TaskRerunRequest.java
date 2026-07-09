package com.onelake.orchestration.dto;

/**
 * 节点重跑请求体。
 *
 * <p>{@code mode} 支持 SINGLE 与 DOWNSTREAM。
 */
public record TaskRerunRequest(String mode) {
}
