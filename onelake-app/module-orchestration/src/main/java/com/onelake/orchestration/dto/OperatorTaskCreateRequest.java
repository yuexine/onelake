package com.onelake.orchestration.dto;

/**
 * 从锁定版本算子创建标准流水线节点的请求。
 *
 * @param operatorRef 算子稳定引用
 * @param version 精确锁定版本
 * @param position 画布坐标
 */
public record OperatorTaskCreateRequest(
        String operatorRef,
        String version,
        Position position
) {
    /** 画布坐标由前端拖入位置提供，两个方向都必须存在。 */
    public record Position(Integer x, Integer y) {
    }
}
