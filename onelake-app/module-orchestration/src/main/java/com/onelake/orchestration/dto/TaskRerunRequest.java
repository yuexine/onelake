package com.onelake.orchestration.dto;

/**
 * 节点重跑请求体。
 *
 * <p>{@code mode} 支持 SINGLE 与 DOWNSTREAM。
 *
 * @param mode SINGLE 仅重跑目标节点；DOWNSTREAM 续跑未成功下游
 */
public record TaskRerunRequest(String mode) {
}
