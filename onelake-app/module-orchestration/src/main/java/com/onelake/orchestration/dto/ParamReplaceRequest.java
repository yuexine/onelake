package com.onelake.orchestration.dto;

import java.util.List;

/**
 * 流水线参数的定向替换请求。
 *
 * @param scope PIPELINE 或 TASK
 * @param taskKey TASK 作用域的目标节点；PIPELINE 时为空
 * @param params 目标作用域的完整参数列表，空数组表示清空该作用域
 */
public record ParamReplaceRequest(
        String scope,
        String taskKey,
        List<ParamDTO> params
) {
}
